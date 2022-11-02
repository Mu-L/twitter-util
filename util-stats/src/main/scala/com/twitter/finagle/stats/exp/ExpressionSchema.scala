package com.twitter.finagle.stats.exp

import com.twitter.finagle.stats.HistogramFormatter
import com.twitter.finagle.stats.MetricUnit
import com.twitter.finagle.stats.SourceRole
import com.twitter.finagle.stats.Unspecified
import com.twitter.finagle.stats.exp.HistogramComponent.Avg
import com.twitter.finagle.stats.exp.HistogramComponent.Count
import com.twitter.finagle.stats.exp.HistogramComponent.Max
import com.twitter.finagle.stats.exp.HistogramComponent.Min
import com.twitter.finagle.stats.exp.HistogramComponent.Percentile
import com.twitter.finagle.stats.exp.HistogramComponent.Sum

/**
 * ExpressionSchema is builder class that construct an expression with its metadata.
 *
 * @param name  this is going to be an important query key when fetching expressions
 * @param labels  service related information
 * @param namespace  a list of namespaces the expression belongs to, this is usually
 *                    used to indicate a tenant in a multi-tenancy systems or similar concepts.
 *                    For standalone services, this should be empty.
 * @param expr  class representation of the expression, see [[Expression]]
 * @param bounds  thresholds for this expression
 * @param description human-readable description of an expression's significance
 * @param unit the unit associated with the metrics value (milliseconds, megabytes, requests, etc)
 * @param exprQuery string representation of the expression
 */
case class ExpressionSchema private (
  name: String,
  labels: Map[String, String],
  expr: Expression,
  namespace: Seq[String],
  bounds: Bounds,
  description: String,
  unit: MetricUnit,
  exprQuery: String) {
  def withBounds(bounds: Bounds): ExpressionSchema = copy(bounds = bounds)

  def withDescription(description: String): ExpressionSchema = copy(description = description)

  def withUnit(unit: MetricUnit): ExpressionSchema = copy(unit = unit)

  /**
   * Configure the expression with the given namespaces. The path can be composed of segments or
   * a single string. This is for multi-tenancy system tenants or similar concepts and should
   * remain unset for standalone services.
   */
  def withNamespace(name: String*): ExpressionSchema =
    copy(namespace = this.namespace ++ name)

  def withLabel(labelName: String, labelValue: String): ExpressionSchema =
    copy(labels = labels + (labelName -> labelValue))

  private[finagle] def withRole(role: SourceRole): ExpressionSchema =
    withLabel(ExpressionSchema.Role, role.toString)

  private[finagle] def withServiceName(name: String): ExpressionSchema =
    withLabel(ExpressionSchema.ServiceName, name)

  def schemaKey(): ExpressionSchemaKey = {
    ExpressionSchemaKey(name, labels, namespace)
  }
}

/**
 * ExpressionSchemaKey is a class that exists to serve as a key into a Map of ExpressionSchemas.
 * It is simply a subset of the fields of the ExpressionSchema. Namely:
 * @param name
 * @param labels
 * @param namespaces
 */
case class ExpressionSchemaKey(
  name: String,
  labels: Map[String, String],
  namespaces: Seq[String])

// expose for testing in twitter-server
private[twitter] object ExpressionSchema {

  case class ExpressionCollisionException(msg: String) extends IllegalArgumentException(msg)

  val Role: String = "role"
  val ServiceName: String = "service_name"
  val ProcessPath: String = "process_path"

  def apply(name: String, expr: Expression): ExpressionSchema = {
    val preLabels = expr match {
      case histoExpr: HistogramExpression => histogramLabel(histoExpr)
      case _ => Map.empty[String, String]
    }

    ExpressionSchema(
      name = name,
      labels = preLabels,
      namespace = Seq.empty,
      expr = expr,
      bounds = Unbounded.get,
      description = "Unspecified",
      unit = Unspecified,
      exprQuery = "")
  }

  private def histogramLabel(histoExpr: HistogramExpression): Map[String, String] = {
    val defaultHistogramFormatter = HistogramFormatter.default
    val value = histoExpr.component match {
      case Percentile(percentile) => defaultHistogramFormatter.labelPercentile(percentile)
      case Min => defaultHistogramFormatter.labelMin
      case Max => defaultHistogramFormatter.labelMax
      case Avg => defaultHistogramFormatter.labelAverage
      case Sum => defaultHistogramFormatter.labelSum
      case Count => defaultHistogramFormatter.labelCount
    }
    Map("bucket" -> value)
  }
}
