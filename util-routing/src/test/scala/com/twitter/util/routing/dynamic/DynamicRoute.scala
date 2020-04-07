package com.twitter.util.routing.dynamic

import com.twitter.util.{ClosableOnce, Future, Time}

// example of a Route for a dynamic input
private[routing] case class DynamicRoute(
  method: Method,
  canHandle: Request => Boolean,
  handle: Request => Future[Result])
    extends ClosableOnce {
  override protected def closeOnce(deadline: Time): Future[Unit] = Future.Done
}
