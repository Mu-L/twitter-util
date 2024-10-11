package com.twitter.jvm

import com.sun.management.GarbageCollectionNotificationInfo
import com.twitter.conversions.StringOps._
import com.twitter.finagle.stats.MetricBuilder.GaugeType
import com.twitter.finagle.stats.Bytes
import com.twitter.finagle.stats.Milliseconds
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.stats.exp.Expression
import com.twitter.finagle.stats.exp.ExpressionSchema
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object JvmStats {
  // set used for keeping track of jvm gauges (otherwise only weakly referenced)
  private[this] val gauges = mutable.Set.empty[Any]

  @volatile
  private[this] var allocations: Allocations = _

  def register(statsReceiver: StatsReceiver): Unit = {
    val stats = statsReceiver.scope("jvm")

    val mem = ManagementFactory.getMemoryMXBean()

    def heap = mem.getHeapMemoryUsage()
    val heapStats = stats.scope("heap")
    val heapUsedGauge = heapStats.addGauge("used") { heap.getUsed().toFloat }
    gauges.add(heapStats.addGauge("committed") { heap.getCommitted().toFloat })
    gauges.add(heapStats.addGauge("max") { heap.getMax().toFloat })
    gauges.add(heapUsedGauge)

    def nonHeap = mem.getNonHeapMemoryUsage()
    val nonHeapStats = stats.scope("nonheap")
    gauges.add(nonHeapStats.addGauge("committed") { nonHeap.getCommitted().toFloat })
    gauges.add(nonHeapStats.addGauge("max") { nonHeap.getMax().toFloat })
    gauges.add(nonHeapStats.addGauge("used") { nonHeap.getUsed().toFloat })

    val threads = ManagementFactory.getThreadMXBean()
    val threadStats = stats.scope("thread")
    gauges.add(threadStats.addGauge("daemon_count") { threads.getDaemonThreadCount().toFloat })
    gauges.add(threadStats.addGauge("count") { threads.getThreadCount().toFloat })
    gauges.add(threadStats.addGauge("peak_count") { threads.getPeakThreadCount().toFloat })

    val runtime = ManagementFactory.getRuntimeMXBean()
    val uptime = stats.addGauge("uptime") { runtime.getUptime().toFloat }
    gauges.add(uptime)
    gauges.add(stats.addGauge("start_time") { runtime.getStartTime().toFloat })
    gauges.add(stats.addGauge("spec_version") { runtime.getSpecVersion.toFloat })

    val os = ManagementFactory.getOperatingSystemMXBean()
    gauges.add(stats.addGauge("num_cpus") { os.getAvailableProcessors().toFloat })
    os match {
      case unix: com.sun.management.UnixOperatingSystemMXBean =>
        val fileDescriptorCountGauge = stats.addGauge("fd_count") {
          unix.getOpenFileDescriptorCount.toFloat
        }
        gauges.add(fileDescriptorCountGauge)
        gauges.add(stats.addGauge("fd_limit") { unix.getMaxFileDescriptorCount.toFloat })
        // register expression
        stats.registerExpression(
          ExpressionSchema("file_descriptors", Expression(fileDescriptorCountGauge.metadata))
            .withLabel(ExpressionSchema.Role, "jvm")
            .withDescription(
              "Total file descriptors used by the service. If it continuously increasing over time, then potentially files or connections aren't being closed"))
      case _ =>
    }

    val compilerStats = stats.scope("compiler");
    gauges.add(compilerStats.addGauge("graal") {
      System.getProperty("jvmci.Compiler") match {
        case "graal" => 1
        case _ => 0
      }
    })

    ManagementFactory.getCompilationMXBean() match {
      case null =>
      case compilation =>
        val compilationStats = stats.scope("compilation")
        gauges.add(
          compilationStats.addGauge(
            compilationStats.metricBuilder(GaugeType).withCounterishGauge.withName("time_msec")) {
            compilation.getTotalCompilationTime().toFloat
          })
    }

    val classes = ManagementFactory.getClassLoadingMXBean()
    val classLoadingStats = stats.scope("classes")
    gauges.add(
      classLoadingStats.addGauge(
        classLoadingStats.metricBuilder(GaugeType).withCounterishGauge.withName("total_loaded")) {
        classes.getTotalLoadedClassCount().toFloat
      })
    gauges.add(
      classLoadingStats.addGauge(
        classLoadingStats.metricBuilder(GaugeType).withCounterishGauge.withName("total_unloaded")) {
        classes.getUnloadedClassCount().toFloat
      })
    gauges.add(
      classLoadingStats.addGauge("current_loaded") { classes.getLoadedClassCount().toFloat }
    )

    val memPool = ManagementFactory.getMemoryPoolMXBeans.asScala
    val memStats = stats.scope("mem")
    val currentMem = memStats.scope("current")
    val postGCStats = memStats.scope("postGC")
    memPool.foreach { pool =>
      val name = pool.getName.regexSub("""[^\w]""".r) { m => "_" }
      if (pool.getCollectionUsage != null) {
        def usage = pool.getCollectionUsage // this is a snapshot, we can't reuse the value
        gauges.add(postGCStats.addGauge(name, "used") { usage.getUsed.toFloat })
      }
      if (pool.getUsage != null) {
        def usage = pool.getUsage // this is a snapshot, we can't reuse the value
        val usageGauge = currentMem.addGauge(name, "used") { usage.getUsed.toFloat }
        gauges.add(usageGauge)
        gauges.add(currentMem.addGauge(name, "max") { usage.getMax.toFloat })

        // register memory usage expression
        currentMem.registerExpression(
          ExpressionSchema("memory_pool", Expression(usageGauge.metadata))
            .withLabel(ExpressionSchema.Role, "jvm")
            .withLabel("kind", name)
            .withUnit(Bytes)
            .withDescription(
              s"The current estimate of the amount of space within the $name memory pool holding allocated objects in bytes"))
      }
    }
    gauges.add(postGCStats.addGauge("used") {
      memPool.flatMap(p => Option(p.getCollectionUsage)).map(_.getUsed).sum.toFloat
    })
    gauges.add(currentMem.addGauge("used") {
      memPool.flatMap(p => Option(p.getUsage)).map(_.getUsed).sum.toFloat
    })

    // the Hotspot JVM exposes the full size that the metaspace can grow to
    // which differs from the value exposed by `MemoryUsage.getMax` from above
    val jvm = Jvm()
    jvm.metaspaceUsage.foreach { usage =>
      gauges.add(memStats.scope("metaspace").addGauge("max_capacity") {
        usage.maxCapacity.inBytes.toFloat
      })
    }

    val spStats = stats.scope("safepoint")
    gauges.add(
      spStats.addGauge(
        spStats.metricBuilder(GaugeType).withCounterishGauge.withName("sync_time_millis")) {
        jvm.safepoint.syncTimeMillis.toFloat
      })
    gauges.add(
      spStats.addGauge(
        spStats.metricBuilder(GaugeType).withCounterishGauge.withName("total_time_millis")) {
        jvm.safepoint.totalTimeMillis.toFloat
      })
    gauges.add(
      spStats.addGauge(spStats.metricBuilder(GaugeType).withCounterishGauge.withName("count")) {
        jvm.safepoint.count.toFloat
      })

    ManagementFactory.getPlatformMXBeans(classOf[BufferPoolMXBean]) match {
      case null =>
      case jBufferPool =>
        val bufferPoolStats = memStats.scope("buffer")
        jBufferPool.asScala.foreach { bp =>
          val name = bp.getName
          gauges.add(bufferPoolStats.addGauge(name, "count") { bp.getCount.toFloat })
          gauges.add(bufferPoolStats.addGauge(name, "used") { bp.getMemoryUsed.toFloat })
          gauges.add(bufferPoolStats.addGauge(name, "max") { bp.getTotalCapacity.toFloat })
        }
    }

    val gcPool = ManagementFactory.getGarbageCollectorMXBeans.asScala
    val gcStats = stats.scope("gc")
    gcPool.foreach { gc =>
      val name = gc.getName.regexSub("""[^\w]""".r) { m => "_" }
      val poolCycles =
        gcStats.addGauge(
          gcStats.metricBuilder(GaugeType).withCounterishGauge.withName(name, "cycles")) {
          gc.getCollectionCount.toFloat
        }
      val poolMsec = gcStats.addGauge(
        gcStats.metricBuilder(GaugeType).withCounterishGauge.withName(name, "msec")) {
        gc.getCollectionTime.toFloat
      }

      val gcPauseStat = gcStats.stat(name, "pause_msec")
      gc.asInstanceOf[NotificationEmitter].addNotificationListener(
          new NotificationListener {
            override def handleNotification(
              notification: Notification,
              handback: Any
            ): Unit = {
              notification.getType
              if (notification.getType == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                gcPauseStat.add(
                  GarbageCollectionNotificationInfo
                    .from(notification.getUserData
                      .asInstanceOf[CompositeData]).getGcInfo.getDuration)
              }
            }
          },
          null,
          null
        )

      gcStats.registerExpression(
        ExpressionSchema(s"gc_cycles", Expression(poolCycles.metadata))
          .withLabel(ExpressionSchema.Role, "jvm")
          .withLabel("gc_pool", name)
          .withDescription(
            s"The total number of collections that have occurred for the $name gc pool"))
      gcStats.registerExpression(ExpressionSchema(s"gc_latency", Expression(poolMsec.metadata))
        .withLabel(ExpressionSchema.Role, "jvm")
        .withLabel("gc_pool", name)
        .withUnit(Milliseconds)
        .withDescription(
          s"The total elapsed time spent doing collections for the $name gc pool in milliseconds"))

      gauges.add(poolCycles)
      gauges.add(poolMsec)
    }

    // note, these could be -1 if the collector doesn't have support for it.
    val cycles =
      gcStats.addGauge(gcStats.metricBuilder(GaugeType).withCounterishGauge.withName("cycles")) {
        gcPool.map(_.getCollectionCount).filter(_ > 0).sum.toFloat
      }
    val msec =
      gcStats.addGauge(gcStats.metricBuilder(GaugeType).withCounterishGauge.withName("msec")) {
        gcPool.map(_.getCollectionTime).filter(_ > 0).sum.toFloat
      }

    gauges.add(cycles)
    gauges.add(msec)
    allocations = new Allocations(gcStats)
    allocations.start()
    if (allocations.trackingEden) {
      val allocationStats = memStats.scope("allocations")
      val eden = allocationStats.scope("eden")
      gauges.add(eden.addGauge("bytes") { allocations.eden.toFloat })
    }

    // return ms from ns while retaining precision
    gauges.add(stats.addGauge("application_time_millis") { jvm.applicationTime.toFloat / 1000000 })
    gauges.add(stats.addGauge("tenuring_threshold") { jvm.tenuringThreshold.toFloat })

    // register metric expressions
    stats.registerExpression(
      ExpressionSchema("jvm_uptime", Expression(uptime.metadata))
        .withLabel(ExpressionSchema.Role, "jvm")
        .withUnit(Milliseconds)
        .withDescription("The uptime of the JVM in milliseconds"))
    gcStats.registerExpression(
      ExpressionSchema("gc_cycles", Expression(cycles.metadata))
        .withLabel(ExpressionSchema.Role, "jvm")
        .withDescription("The total number of collections that have occurred"))
    gcStats.registerExpression(
      ExpressionSchema("gc_latency", Expression(msec.metadata))
        .withLabel(ExpressionSchema.Role, "jvm")
        .withUnit(Milliseconds)
        .withDescription("The total elapsed time spent doing collections in milliseconds"))
    heapStats.registerExpression(
      ExpressionSchema("memory_pool", Expression(heapUsedGauge.metadata))
        .withLabel(ExpressionSchema.Role, "jvm")
        .withLabel("kind", "Heap")
        .withUnit(Bytes)
        .withDescription("Heap in use in bytes"))
  }
}
