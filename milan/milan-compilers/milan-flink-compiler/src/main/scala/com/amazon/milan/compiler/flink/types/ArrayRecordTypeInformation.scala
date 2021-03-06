package com.amazon.milan.compiler.flink.types

import java.util

import com.amazon.milan.compiler.scala.RuntimeEvaluator
import com.amazon.milan.typeutil.FieldDescriptor
import org.apache.commons.lang.builder.HashCodeBuilder
import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeutils.TypeSerializer


class ArrayRecordTypeInformation(val fields: Array[FieldTypeInformation]) extends TypeInformation[ArrayRecord] {
  private val hashCodeValue = HashCodeBuilder.reflectionHashCode(this)

  override def createSerializer(executionConfig: ExecutionConfig): TypeSerializer[ArrayRecord] = {
    new ArrayRecordTypeSerializer(executionConfig, this.fields)
  }

  override def getGenericParameters: util.Map[String, TypeInformation[_]] = {
    new util.HashMap[String, TypeInformation[_]]()
  }

  override def getArity: Int = this.fields.length

  override def getTotalFields: Int = this.getArity + this.fields.map(_.typeInfo.getTotalFields).sum

  override def getTypeClass: Class[ArrayRecord] = classOf[ArrayRecord]

  override def isBasicType: Boolean = false

  override def isKeyType: Boolean = false

  override def isSortKeyType: Boolean = false

  override def isTupleType: Boolean = true

  override def canEqual(o: Any): Boolean = {
    o match {
      case _: ArrayRecordTypeInformation =>
        true

      case _ =>
        false
    }
  }

  override def toString: String = {
    "TupleStream" + this.fields.map(_.typeInfo.toString).mkString("[", ", ", "]")
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case o: ArrayRecordTypeInformation =>
        this.fields.sameElements(o.fields)
    }
  }

  override def hashCode(): Int = this.hashCodeValue
}


case class FieldTypeInformation(fieldName: String, typeInfo: TypeInformation[_]) extends Serializable
