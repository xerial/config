/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.core

import wvlet.core.WvletOps.{ConvertOp, FilterOp, MapOp, MkStringOp}
import wvlet.core.rx.{Flow, FlowBase}

trait WvletOp[A]

/**
  *
  */
object WvletOps {

  case class SeqOp[A](seq: Seq[A]) extends WvSeq[A]
  case class MapOp[A, B](prev: WvSeq[A], f: A => B) extends WvSeq[B]
  case class FilterOp[A](prev: WvSeq[A], cond: A => Boolean) extends WvSeq[A]

  case class ConvertOp[A, R](prev: WvSeq[A], out: Output[R]) extends WvSeq[R]
  case class MkStringOp[A](prev: WvSeq[A], separator: String) extends WvSingle[String]


}

trait WvSingle[A] extends WvletOp[A] {

}

trait WvSeq[A] extends WvletOp[A] {
  self =>
  def map[B](f: A => B): WvSeq[B] = MapOp(self, f)
  def filter(cond: A => Boolean): WvSeq[A] = FilterOp(self, cond)
  def |[R](out: Output[R]): WvSeq[R] = ConvertOp(self, out)
  def mkString(separator: String): WvSingle[String] = MkStringOp(self, separator)

  def stream(flow: Flow[A]) : rx.Stream = {
    val s = rx.Stream.build(self, flow)
    s.run(Long.MaxValue)
    s
  }

  def stream[U](handler: A => U) : rx.Stream = {
    val flow = new Flow[A] {
      override def onNext(elem: A): Unit = handler(elem)
      override def onStart: Unit = {}
      override def onError(e: Throwable): Unit = {}
      override def onComplete: Unit = {}
    }
    stream(flow)
  }
}