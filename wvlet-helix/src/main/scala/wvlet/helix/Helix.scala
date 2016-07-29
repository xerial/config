package wvlet.helix


import java.util.concurrent.ConcurrentHashMap

import wvlet.log.LogSupport
import wvlet.obj.{ObjectSchema, ObjectType, TypeUtil}

import scala.reflect.ClassTag
import scala.language.experimental.macros

object Helix {

  sealed trait Binding {
    def from :  ObjectType
  }
  case class ClassBinding(from:ObjectType, to:ObjectType) extends Binding
  case class InstanceBinding(from:ObjectType, to:Any) extends Binding
  case class SingletonBinding(from:ObjectType) extends Binding

}

import Helix._

/**
  *
  */
class Helix extends LogSupport {

  private val binding = Seq.newBuilder[Binding]

  def bind[A](implicit a:ClassTag[A]) : Bind = {
    new Bind(this, ObjectType(a.runtimeClass))
  }

  def bind[A](obj:A)(implicit a:ClassTag[A]) : Helix = {
    binding += InstanceBinding(ObjectType(a.runtimeClass), obj)
    this
  }

  def getContext : Context = {
    new InternalContext(binding.result)
  }

  def addBinding(b:Binding) : Helix = {
    binding += b
    this
  }
}


class Bind(h:Helix, from:ObjectType) extends LogSupport {

  def to[B](implicit ev:ClassTag[B]) {
    val to = ObjectType(ev.runtimeClass)
    if(from == to) {
      warn(s"Binding to the same type will be ignored: ${from.name}")
    }
    else {
      h.addBinding(ClassBinding(from, to))
    }
  }

  def toInstance(any:Any) {
    h.addBinding(InstanceBinding(from, any))
  }

  def asSingleton {
    h.addBinding(SingletonBinding(from))
  }
}

/**
  * Context tracks the dependencies of objects and use them to instanciate objects
  */
trait Context {

  /**
    * Creates an instance of the given type A.
    *
    * @tparam A
    * @return object
    */
  def get[A:ClassTag] : A

  def weave[A:ClassTag] : A = macro HelixMacros.weave[A]

}

trait ContextListener {

  def afterInjection(t:ObjectType, injectee:AnyRef)
}


private[helix] class InternalContext(binding:Seq[Binding]) extends wvlet.helix.Context with LogSupport {

  import scala.collection.JavaConversions._
  private val singletonHolder : collection.mutable.Map[ObjectType, AnyRef] = new ConcurrentHashMap[ObjectType, AnyRef]()

  /**
    * Creates an instance of the given type A.
    *
    * @return object
    */
  def get[A](implicit ev:ClassTag[A]): A = {
    val cl = ev.runtimeClass
    info(s"Get ${cl.getName}")

    newInstance(cl).asInstanceOf[A]
  }

  private def newInstance(cl:Class[_]) : AnyRef = {
    newInstance(ObjectType(cl))
  }

  private def newInstance(t:ObjectType) : AnyRef = {
    val obj = binding.find(_.from == t).map {
      case ClassBinding(from, to) =>
        newInstance(to)
      case InstanceBinding(from, obj) =>
        obj
      case SingletonBinding(from) => {
        singletonHolder.getOrElseUpdate(from, buildInstance(from))
      }
    }
    .getOrElse {
      buildInstance(t)
    }
    obj.asInstanceOf[AnyRef]
  }

  private def buildInstance(t:ObjectType) : AnyRef = {
    val schema = ObjectSchema(t.rawType)
    val args = for (p <- schema.constructor.params) yield {
      newInstance(p.valueType.rawType)
    }
    schema.constructor.newInstance(args).asInstanceOf[AnyRef]
  }

}




