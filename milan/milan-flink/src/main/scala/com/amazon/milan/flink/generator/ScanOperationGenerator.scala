package com.amazon.milan.flink.generator

import com.amazon.milan.flink.runtime._
import com.amazon.milan.program.{ArgCompareExpression, ArgScanExpression, StreamArgMax, StreamArgMin, SumBy, Tree}
import com.amazon.milan.typeutil.TypeDescriptor


case class ScanOperationClassInfo(className: ClassName, stateType: TypeDescriptor[_], outputType: TypeDescriptor[_])


/**
 * Generates [[ScanOperation]] instances from Milan expressions.
 */
trait ScanOperationGenerator {
  val typeLifter: TypeLifter

  import typeLifter._

  /**
   * Generates a class that implements Milan's [[ScanOperation]] interface for an expression.
   */
  def generateScanOperation(context: GeneratorContext,
                            expr: Tree,
                            inputRecordType: TypeDescriptor[_]): ScanOperationClassInfo = {
    expr match {
      case argCompareExpr: ArgCompareExpression =>
        this.generateArgCompareScanOperation(context.output, argCompareExpr, inputRecordType)

      case argScanExpr: ArgScanExpression =>
        this.generateAssociativeScanOperation(context.output, argScanExpr, inputRecordType)
    }
  }

  def generateScanOperationAggregateFunction(output: GeneratorOutputs,
                                             streamIdentifier: String,
                                             inputRecordType: TypeDescriptor[_],
                                             keyType: TypeDescriptor[_],
                                             scanOperationInfo: ScanOperationClassInfo): ValName = {

    val scanOpVal = output.newValName(s"stream_${streamIdentifier}_scanOperation_")
    val aggregateFunctionVal = output.newValName(s"stream_${streamIdentifier}_aggregateFunction")

    val codeBlock =
      q"""val $scanOpVal = new ${scanOperationInfo.className}()
         |val $aggregateFunctionVal = new ${nameOf[ScanOperationAggregateFunction[Any, Product, Any, Any]]}[${inputRecordType.toFlinkTerm}, ${keyType.toTerm}, ${scanOperationInfo.stateType.toTerm}, ${scanOperationInfo.outputType.toFlinkTerm}](
         |  $scanOpVal,
         |  ${liftTypeDescriptorToTypeInformation(inputRecordType)},
         |  ${liftTypeDescriptorToTypeInformation(keyType)},
         |  ${liftTypeDescriptorToTypeInformation(scanOperationInfo.stateType)},
         |  ${liftTypeDescriptorToTypeInformation(scanOperationInfo.outputType)})
         |""".strip

    output.appendMain(codeBlock)

    aggregateFunctionVal
  }

  def applyUnpackOptionProcessFunction(output: GeneratorOutputs,
                                       streamIdentifier: String,
                                       inputStream: GeneratedDataStream): GeneratedUnkeyedDataStream = {
    val outputStreamVal = output.newStreamValName(streamIdentifier + "_records_")
    val processFunctionVal = output.newValName(s"stream_${streamIdentifier}_optionProcessor_")

    val codeBlock =
      q"""val $processFunctionVal = new ${nameOf[UnpackOptionProcessFunction[Any, Product]]}[${inputStream.recordType.toFlinkTerm}, ${inputStream.keyType.toTerm}](
         |  ${liftTypeDescriptorToTypeInformation(inputStream.recordType)},
         |  ${liftTypeDescriptorToTypeInformation(inputStream.keyType)})
         |val $outputStreamVal = ${inputStream.streamVal}.process($processFunctionVal)
         |""".strip

    output.appendMain(codeBlock)

    GeneratedUnkeyedDataStream(streamIdentifier, outputStreamVal, inputStream.recordType, inputStream.keyType, isContextual = false)
  }

  private def generateComposedScanOperation(output: GeneratorOutputs,
                                            identifier: String,
                                            firstScanOperationVal: ValName,
                                            secondScanOperationVal: ValName): ValName = {
    val composedScanVal = output.newValName(s"stream_${identifier}_scanOperation_")
    val codeBlock = q"val $composedScanVal = ${nameOf[ScanOperation[Any, Any, Any]]}.compose($firstScanOperationVal, $secondScanOperationVal)"

    output.appendMain(codeBlock)

    composedScanVal
  }

  private def generateArgCompareScanOperation(output: GeneratorOutputs,
                                              argCompareExpr: ArgCompareExpression,
                                              recordType: TypeDescriptor[_]): ScanOperationClassInfo = {

    val className = output.newClassName(s"ScanOperation_${argCompareExpr.nodeName}_")

    val argType = argCompareExpr.argExpr.tpe

    val greaterThanBody =
      argCompareExpr match {
        case _: StreamArgMax => "ordering.gt(arg1, arg2)"
        case _: StreamArgMin => "ordering.lt(arg1, arg2)"
      }

    val getArgDef = output.scalaGenerator.getScalaFunctionDef("getArg", argCompareExpr.argExpr)

    val classDef =
      q"""class $className
         |  extends ${nameOf[ArgMaxScanOperation[Any, Any]]}[${recordType.toFlinkTerm}, ${argType.toFlinkTerm}](
         |    ${liftTypeDescriptorToTypeInformation(recordType)},
         |    ${liftTypeDescriptorToTypeInformation(argType)}) {
         |
         |    override def greaterThan(ordering: Ordering[${argType.toFlinkTerm}], arg1: ${argType.toFlinkTerm}, arg2: ${argType.toFlinkTerm}): Boolean = {
         |      ${code(greaterThanBody)}
         |    }
         |
         |    override ${code(getArgDef).indentTail(1)}
         |}
         |""".strip

    output.addClassDef(classDef)

    ScanOperationClassInfo(className, TypeDescriptor.optionOf(argType), recordType)
  }

  /**
   * Generates a [[ScanOperation]] class for an [[ArgScanExpression]].
   */
  private def generateAssociativeScanOperation(output: GeneratorOutputs,
                                               argScanExpr: ArgScanExpression,
                                               inputRecordType: TypeDescriptor[_]): ScanOperationClassInfo = {
    val argType = argScanExpr.argExpr.tpe
    val outputRecordType = argScanExpr.outputExpr.tpe.toFlinkRecordType

    val getArgDef = output.scalaGenerator.getScalaFunctionDef("getArg", argScanExpr.argExpr)

    val getOutputDef = output.scalaGenerator.getScalaFunctionDef("getOutput", argScanExpr.outputExpr)

    val addDef =
      argScanExpr match {
        case _: SumBy => "numeric.plus(arg1, arg2)"
      }

    val className = output.newClassName(s"ScanOperation_${argScanExpr.nodeName}_")

    val classDef =
      q"""class $className
         |  extends ${nameOf[AssociativeScanOperation[Any, Any, Any]]}[${inputRecordType.toFlinkTerm}, ${argType.toFlinkTerm}, ${outputRecordType.toFlinkTerm}](
         |  ${liftTypeDescriptorToTypeInformation(argType)},
         |  ${liftTypeDescriptorToTypeInformation(outputRecordType)}) {
         |
         |  override ${code(getArgDef).indentTail(1)}
         |
         |  override ${code(getOutputDef).indentTail(1)}
         |
         |  override def add(numeric: Numeric[${argType.toFlinkTerm}], arg1: ${argType.toFlinkTerm}, arg2: ${argType.toFlinkTerm}): ${argType.toFlinkTerm} = {
         |    ${code(addDef)}
         |  }
         |
         |}
         |""".strip

    output.addClassDef(classDef)

    ScanOperationClassInfo(className, argType, outputRecordType)
  }
}