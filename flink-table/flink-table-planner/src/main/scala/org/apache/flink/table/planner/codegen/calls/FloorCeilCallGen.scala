/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.codegen.calls

import org.apache.flink.table.planner.codegen.{CodeGeneratorContext, GeneratedExpression}
import org.apache.flink.table.planner.codegen.CodeGenUtils.{getEnum, primitiveTypeTermForType, qualifyMethod, TIMESTAMP_DATA}
import org.apache.flink.table.planner.codegen.GenerateUtils.generateCallIfArgsNotNull
import org.apache.flink.table.types.logical.{LogicalType, LogicalTypeRoot}
import org.apache.flink.table.utils.DateTimeUtils.TimeUnitRange
import org.apache.flink.table.utils.DateTimeUtils.TimeUnitRange._

import java.lang.reflect.Method
import java.util.TimeZone

/** Generates floor/ceil function calls. */
class FloorCeilCallGen(
    arithmeticMethod: Method,
    arithmeticIntegralMethod: Option[Method] = None,
    decimalMethod: Option[Method] = None,
    temporalMethod: Option[Method] = None)
  extends MethodCallGen(arithmeticMethod) {

  override def generate(
      ctx: CodeGeneratorContext,
      operands: Seq[GeneratedExpression],
      returnType: LogicalType): GeneratedExpression = operands.size match {
    // arithmetic
    case 1 =>
      operands.head.resultType.getTypeRoot match {
        case LogicalTypeRoot.FLOAT | LogicalTypeRoot.DOUBLE =>
          super.generate(ctx, operands, returnType)
        case LogicalTypeRoot.DECIMAL =>
          generateCallIfArgsNotNull(ctx, returnType, operands) {
            operandResultTerms =>
              s"${qualifyMethod(decimalMethod.get)}(${operandResultTerms.mkString(", ")})"
          }
        case _ =>
          operands.head // no floor/ceil necessary
      }

    // temporal
    case 2 =>
      val operand = operands.head
      val unit = getEnum(operands(1)).asInstanceOf[TimeUnitRange]
      val internalType = primitiveTypeTermForType(operand.resultType)
      val method = temporalMethod.getOrElse(arithmeticMethod)

      generateCallIfArgsNotNull(ctx, operand.resultType, operands) {
        terms =>
          unit match {
            // for Timestamp with timezone info
            case MILLENNIUM | CENTURY | DECADE | YEAR | QUARTER | MONTH | WEEK | DAY | HOUR |
                MINUTE | SECOND | MILLISECOND
                if terms.length + 1 == method.getParameterCount &&
                  method.getParameterTypes()(terms.length) == classOf[TimeZone] =>
              val timeZone = ctx.addReusableSessionTimeZone()
              val longTerm = s"${terms.head}.getMillisecond()"
              s"""
                 |$TIMESTAMP_DATA.fromEpochMillis(
                 |  ${qualifyMethod(temporalMethod.get)}(${terms(1)},
                 |  $longTerm,
                 |  $timeZone))
                 |""".stripMargin

            // for Unix Date / Unix Time
            case MILLENNIUM | CENTURY | DECADE | YEAR | QUARTER | MONTH | WEEK | DAY =>
              operand.resultType.getTypeRoot match {
                case LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE =>
                  unit match {
                    case DAY =>
                      val longTerm = s"${terms.head}.getMillisecond()"
                      s"""
                         |$TIMESTAMP_DATA.fromEpochMillis(${qualifyMethod(arithmeticMethod)}(
                         |  $longTerm,
                         |  (long) ${unit.startUnit.multiplier.intValue()}))
                       """.stripMargin
                    case _ =>
                      val longTerm = s"${terms.head}.getMillisecond()"
                      s"""
                         |$TIMESTAMP_DATA.fromEpochMillis(
                         |  ${qualifyMethod(temporalMethod.get)}(${terms(1)}, $longTerm))
                   """.stripMargin
                  }

                case _ =>
                  s"""
                     |($internalType) ${qualifyMethod(temporalMethod.get)}(
                     |  ${terms(1)}, ${terms.head})
                     |""".stripMargin
              }
            case _ =>
              operand.resultType.getTypeRoot match {
                case LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE =>
                  val millis = s"${terms.head}.getMillisecond()"

                  unit match {
                    case MILLISECOND =>
                      s"""
                         |$TIMESTAMP_DATA.fromEpochMillis(
                         |    $millis + ${qualifyMethod(arithmeticIntegralMethod.get)}(${terms.head}.getNanoOfMillisecond(), 1000_000L) / 1000_000L)
                        """.stripMargin

                    case _ => s"""
                                 |$TIMESTAMP_DATA.fromEpochMillis(
                                 |  ${qualifyMethod(arithmeticIntegralMethod.get)}(
                                 |    $millis,
                                 |    (long) ${unit.startUnit.multiplier.intValue()}))
                   """.stripMargin

                  }
                case LogicalTypeRoot.DATE =>
                  s"""
                     |  ${terms.head}
                   """.stripMargin
                case _ =>
                  s"""
                     |${qualifyMethod(arithmeticIntegralMethod.get)}(
                     |  ($internalType) ${terms.head},
                     |  ($internalType) ${unit.startUnit.multiplier.intValue()})
                     |""".stripMargin
              }

          }
      }
  }
}
