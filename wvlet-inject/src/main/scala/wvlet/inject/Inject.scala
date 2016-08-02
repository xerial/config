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
package wvlet.inject

import java.util.concurrent.ConcurrentHashMap

import wvlet.inject.InjectionException.{CYCLIC_DEPENDENCY, MISSING_SESSION}
import wvlet.log.LogSupport
import wvlet.obj.{ObjectSchema, ObjectType}

import scala.language.experimental.macros
import scala.reflect.ClassTag
import scala.util.{Failure, Try}

object Inject extends LogSupport {

  sealed trait Binding {
    def from: ObjectType
  }
  case class ClassBinding(from: ObjectType, to: ObjectType) extends Binding
  case class InstanceBinding(from: ObjectType, to: Any) extends Binding
  case class SingletonBinding(from: ObjectType, to: ObjectType, isEager: Boolean) extends Binding
  case class ProviderBinding[A](from: ObjectType, provider: ObjectType => A) extends Binding

  def findSessionAccess[A](cl: Class[A]): Option[AnyRef => Session] = {

    debug(s"Find session for ${cl}")

    def returnsSession(c: Class[_]) = {
      classOf[wvlet.inject.Session].isAssignableFrom(c)
    }

    // find val or def that returns wvlet.inject.Context
    val schema = ObjectSchema(cl)

    def findSessionFromMethods: Option[AnyRef => Session] =
      schema
      .methods
      .find(x => returnsSession(x.valueType.rawType) && x.params.isEmpty)
      .map { contextGetter => { obj: AnyRef => contextGetter.invoke(obj).asInstanceOf[Session] }
      }

    def findSessionFromParams: Option[AnyRef => Session] = {
      // Find parameters
      schema
      .parameters
      .find(p => returnsSession(p.valueType.rawType))
      .map { contextParam => { obj: AnyRef => contextParam.get(obj).asInstanceOf[Session] } }
    }

    def findEmbeddedSession: Option[AnyRef => Session] = {
      // Find any embedded context
      val m = Try(cl.getDeclaredMethod("__current_session")).toOption
      m.map { m => { obj: AnyRef => m.invoke(obj).asInstanceOf[Session] }
      }
    }

    findSessionFromMethods
    .orElse(findSessionFromParams)
    .orElse(findEmbeddedSession)
  }

  def getSession[A](enclosingObj: A): Option[Session] = {
    require(enclosingObj != null, "enclosinbObj is null")
    findSessionAccess(enclosingObj.getClass).flatMap { access =>
      Try(access.apply(enclosingObj.asInstanceOf[AnyRef])).toOption
    }
  }

  def findSession[A](enclosingObj: A): Session = {
    val cl = enclosingObj.getClass
    getSession(enclosingObj).getOrElse {
      error(s"No wvlet.inject.Session is found in the scope: ${ObjectType.of(cl)}")
      throw new InjectionException(MISSING_SESSION(ObjectType.of(cl)))
    }
  }

}

import wvlet.inject.Inject._

import scala.reflect.runtime.{universe => ru}

/**
  *
  */
class Inject extends LogSupport {

  private val binding  = Seq.newBuilder[Binding]
  private val listener = Seq.newBuilder[SessionListener]

  def bind[A](implicit a: ru.TypeTag[A]): Bind = {
    bind(ObjectType.of(a.tpe))
  }
  def bind(t: ObjectType): Bind = {
    debug(s"Bind ${t.name} [${t.rawType}]")
    val b = new Bind(this, t)
    b
  }

  def addListner[A](l: SessionListener): Inject = {
    listener += l
    this
  }

  def newContext: Session = {

    // Override preceding bindings
    val b = binding.result()
    val takesLastBiding = for ((key, lst) <- b.groupBy(_.from)) yield {
      lst.last
    }

    new SessionImpl(takesLastBiding.toIndexedSeq, listener.result())
  }

  def addBinding(b: Binding): Inject = {
    debug(s"Add binding: $b")
    binding += b
    this
  }
}

class Bind(h: Inject, from: ObjectType) extends LogSupport {

  def to[B](implicit ev: ru.TypeTag[B]) {
    val to = ObjectType.of(ev.tpe)
    if (from == to) {
      warn(s"Binding to the same type will be ignored: ${from.name}")
    }
    else {
      h.addBinding(ClassBinding(from, to))
    }
  }

  def toProvider[A: ClassTag](provider: ObjectType => A) {
    h.addBinding(ProviderBinding(from, provider))
  }

  def toSingletonOf[B](implicit ev: ru.TypeTag[B]) {
    val to = ObjectType.of(ev.tpe)
    if (from == to) {
      warn(s"Binding to the same type will be ignored: ${from.name}")
    }
    else {
      h.addBinding(SingletonBinding(from, to, false))
    }
  }

  def toEagerSingletonOf[B](implicit ev: ru.TypeTag[B]) {
    val to = ObjectType.of(ev.tpe)
    if (from == to) {
      warn(s"Binding to the same type will be ignored: ${from.name}")
    }
    else {
      h.addBinding(SingletonBinding(from, to, true))
    }
  }

  def toInstance(any: Any) {
    h.addBinding(InstanceBinding(from, any))
  }

  def toSingleton {
    h.addBinding(SingletonBinding(from, from, false))
  }

  def toEagerSingleton {
    h.addBinding(SingletonBinding(from, from, true))
  }
}

import scala.reflect.runtime.{universe => ru}


