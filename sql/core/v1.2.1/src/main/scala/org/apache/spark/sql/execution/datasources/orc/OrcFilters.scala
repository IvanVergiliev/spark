/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.orc

import org.apache.orc.storage.common.`type`.HiveDecimal
import org.apache.orc.storage.ql.io.sarg.{PredicateLeaf, SearchArgument}
import org.apache.orc.storage.ql.io.sarg.SearchArgument.Builder
import org.apache.orc.storage.ql.io.sarg.SearchArgument.TruthValue
import org.apache.orc.storage.ql.io.sarg.SearchArgumentFactory.newBuilder
import org.apache.orc.storage.serde2.io.HiveDecimalWritable

import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types._

/**
 * Helper object for building ORC `SearchArgument`s, which are used for ORC predicate push-down.
 *
 * Due to limitation of ORC `SearchArgument` builder, we had to implement separate checking and
 * conversion code paths to make sure we only convert predicates that are known to be convertible.
 *
 * An ORC `SearchArgument` must be built in one pass using a single builder.  For example, you can't
 * build `a = 1` and `b = 2` first, and then combine them into `a = 1 AND b = 2`.  This is quite
 * different from the cases in Spark SQL or Parquet, where complex filters can be easily built using
 * existing simpler ones.
 *
 * The annoying part is that, `SearchArgument` builder methods like `startAnd()`, `startOr()`, and
 * `startNot()` mutate internal state of the builder instance.  This forces us to translate all
 * convertible filters with a single builder instance. However, if we try to translate a filter
 * before checking whether it can be converted or not, we may end up with a builder whose internal
 * state is inconsistent in the case of an inconvertible filter.
 *
 * For example, to convert an `And` filter with builder `b`, we call `b.startAnd()` first, and then
 * try to convert its children.  Say we convert `left` child successfully, but find that `right`
 * child is inconvertible.  Alas, `b.startAnd()` call can't be rolled back, and `b` is inconsistent
 * now.
 *
 * The workaround employed here is that, for `And`/`Or`/`Not`, we explicitly check if the children
 * are convertible, and only do the actual conversion when the children are proven to be
 * convertible.
 *
 * P.S.: Hive seems to use `SearchArgument` together with `ExprNodeGenericFuncDesc` only.  Usage of
 * builder methods mentioned above can only be found in test code, where all tested filters are
 * known to be convertible.
 */
private[sql] object OrcFilters extends OrcFiltersBase {

  /**
   * Create ORC filter as a SearchArgument instance.
   */
  def createFilter(schema: StructType, filters: Seq[Filter]): Option[SearchArgument] = {
    val dataTypeMap = schema.map(f => f.name -> f.dataType).toMap
    for {
      // Combines all convertible filters using `And` to produce a single conjunction
      conjunction <- buildTree(convertibleFilters(schema, dataTypeMap, filters))
      // Then tries to build a single ORC `SearchArgument` for the conjunction predicate
      builder <- buildSearchArgument(dataTypeMap, conjunction, newBuilder)
    } yield builder.build()
  }

  def convertibleFilters(
      schema: StructType,
      dataTypeMap: Map[String, DataType],
      filters: Seq[Filter]): Seq[Filter] = {
    for {
      filter <- filters
      _ <- buildSearchArgument(dataTypeMap, filter, newBuilder())
    } yield filter
  }

  /**
   * Get PredicateLeafType which is corresponding to the given DataType.
   */
  private def getPredicateLeafType(dataType: DataType) = dataType match {
    case BooleanType => PredicateLeaf.Type.BOOLEAN
    case ByteType | ShortType | IntegerType | LongType => PredicateLeaf.Type.LONG
    case FloatType | DoubleType => PredicateLeaf.Type.FLOAT
    case StringType => PredicateLeaf.Type.STRING
    case DateType => PredicateLeaf.Type.DATE
    case TimestampType => PredicateLeaf.Type.TIMESTAMP
    case _: DecimalType => PredicateLeaf.Type.DECIMAL
    case _ => throw new UnsupportedOperationException(s"DataType: ${dataType.catalogString}")
  }

  /**
   * Cast literal values for filters.
   *
   * We need to cast to long because ORC raises exceptions
   * at 'checkLiteralType' of SearchArgumentImpl.java.
   */
  private def castLiteralValue(value: Any, dataType: DataType): Any = dataType match {
    case ByteType | ShortType | IntegerType | LongType =>
      value.asInstanceOf[Number].longValue
    case FloatType | DoubleType =>
      value.asInstanceOf[Number].doubleValue()
    case _: DecimalType =>
      new HiveDecimalWritable(HiveDecimal.create(value.asInstanceOf[java.math.BigDecimal]))
    case _ => value
  }

  /**
   * Build a SearchArgument and return the builder so far.
   */
  private def buildSearchArgument(
      dataTypeMap: Map[String, DataType],
      expression: Filter,
      builder: Builder): Option[Builder] = {
    trimNonConvertibleSubtrees(dataTypeMap, expression, canPartialPushDownConjuncts = true)
        .map(createBuilder(dataTypeMap, _, builder))
  }

  private def trimNonConvertibleSubtrees(
      dataTypeMap: Map[String, DataType],
      expression: Filter,
      canPartialPushDownConjuncts: Boolean): Option[Filter] = {
    def getType(attribute: String): PredicateLeaf.Type =
      getPredicateLeafType(dataTypeMap(attribute))

    import org.apache.spark.sql.sources._

    expression match {
      case And(left, right) =>
        val lhs = trimNonConvertibleSubtrees(dataTypeMap, left, canPartialPushDownConjuncts = true)
        val rhs = trimNonConvertibleSubtrees(dataTypeMap, right, canPartialPushDownConjuncts = true)
        if (lhs.isDefined && rhs.isDefined) {
          Some(And(lhs.get, rhs.get))
        } else {
          if (canPartialPushDownConjuncts && (lhs.isDefined || rhs.isDefined)) {
            lhs.orElse(rhs)
          } else {
            None
          }
        }

      case Or(left, right) =>
        for {
          lhs: Filter <-
            trimNonConvertibleSubtrees(dataTypeMap, left, canPartialPushDownConjuncts = false)
          rhs: Filter <-
            trimNonConvertibleSubtrees(dataTypeMap, right, canPartialPushDownConjuncts = false)
        } yield Or(lhs, rhs)

      case Not(child) =>
        val filteredSubtree =
          trimNonConvertibleSubtrees(dataTypeMap, child, canPartialPushDownConjuncts = false)
        filteredSubtree.map(Not(_))

      case EqualTo(attribute, value) if isSearchableType(dataTypeMap(attribute)) => Some(expression)
      case EqualNullSafe(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        Some(expression)
      case LessThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        Some(expression)
      case LessThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        Some(expression)
      case GreaterThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        Some(expression)
      case GreaterThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        Some(expression)
      case IsNull(attribute) if isSearchableType(dataTypeMap(attribute)) => Some(expression)
      case IsNotNull(attribute) if isSearchableType(dataTypeMap(attribute)) => Some(expression)
      case In(attribute, values) if isSearchableType(dataTypeMap(attribute)) => Some(expression)

      case _ => None
    }
  }

  private def createBuilder(
      dataTypeMap: Map[String, DataType],
      expression: Filter,
      builder: Builder): Builder = {
    def getType(attribute: String): PredicateLeaf.Type =
      getPredicateLeafType(dataTypeMap(attribute))

    import org.apache.spark.sql.sources._
    def updateBuilder(subexpression: Filter): Unit = subexpression match {
      case And(left, right) =>
        builder.startAnd()
        updateBuilder(left)
        updateBuilder(right)
        builder.end()

      case Or(left, right) =>
        builder.startOr()
        updateBuilder(left)
        updateBuilder(right)
        builder.end()

      case Not(child) =>
        builder.startNot()
        updateBuilder(child)
        builder.end()

      case EqualTo(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startAnd().equals(quotedName, getType(attribute), castedValue).end()

      case EqualNullSafe(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startAnd().nullSafeEquals(quotedName, getType(attribute), castedValue).end()

      case LessThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startAnd().lessThan(quotedName, getType(attribute), castedValue).end()

      case LessThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startAnd().lessThanEquals(quotedName, getType(attribute), castedValue).end()

      case GreaterThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startNot().lessThanEquals(quotedName, getType(attribute), castedValue).end()

      case GreaterThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        builder.startNot().lessThan(quotedName, getType(attribute), castedValue).end()

      case IsNull(attribute) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        builder.startAnd().isNull(quotedName, getType(attribute)).end()

      case IsNotNull(attribute) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        builder.startNot().isNull(quotedName, getType(attribute)).end()

      case In(attribute, values) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValues = values.map(v => castLiteralValue(v, dataTypeMap(attribute)))
        builder.startAnd().in(quotedName, getType(attribute),
          castedValues.map(_.asInstanceOf[AnyRef]): _*).end()

      case _ => builder.startAnd().literal(TruthValue.YES).end()
    }

    updateBuilder(expression)
    builder
  }
}

