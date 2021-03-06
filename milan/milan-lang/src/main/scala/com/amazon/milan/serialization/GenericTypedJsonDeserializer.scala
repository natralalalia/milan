package com.amazon.milan.serialization

import com.amazon.milan.typeutil.TypeDescriptor
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory


/**
 * A [[JsonDeserializer]] that can deserialize objects written using the [[GenericTypedJsonSerializer]].
 *
 * @param typeNameTransformer A function that converts type names as read from the JSON struct to fully qualified
 *                            type names that can be found by the ClassLoader.
 * @tparam T The type of objects returned.
 */
class GenericTypedJsonDeserializer[T <: SetGenericTypeInfo](typeNameTransformer: String => String)
  extends JsonDeserializer[T] {

  private val logger = Logger(LoggerFactory.getLogger(getClass))

  def this(packageName: String) {
    this(typeName => s"$packageName.$typeName")
  }

  override def deserialize(parser: JsonParser, context: DeserializationContext): T = {
    assert(parser.nextFieldName() == "_type")

    val rawTypeName = parser.nextTextValue()

    // If the stored type name contains a path separator then use it verbatim, otherwise transform it.
    val typeName =
      if (rawTypeName.contains(".")) {
        rawTypeName
      } else {
        this.typeNameTransformer(rawTypeName)
      }

    assert(parser.nextFieldName() == "_genericArgs")
    parser.nextToken()

    val genericArgs = context.readValue[Array[TypeDescriptor[_]]](parser, classOf[Array[TypeDescriptor[_]]]).toList
    parser.nextToken()

    logger.info(s"Deserializing type '$typeName[${genericArgs.map(_.fullName).mkString(", ")}]'.")

    val cls = this.getTypeClass(typeName)
    val javaType = new JavaTypeFactory(context.getTypeFactory).makeJavaType(cls, genericArgs)

    val value = context.readValue[T](parser, javaType)
    value.setGenericArguments(genericArgs)

    value
  }

  protected def getTypeClass(typeName: String): Class[_ <: T] = {
    getClass.getClassLoader.loadClass(typeName).asInstanceOf[Class[T]]
  }
}
