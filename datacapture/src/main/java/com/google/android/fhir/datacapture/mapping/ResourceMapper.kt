/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.mapping

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TimeType
import org.hl7.fhir.r4.model.UrlType

/**
 * Maps [QuestionnaireResponse] s to FHIR resources and vice versa.
 *
 * The process of converting [QuestionnaireResponse] s to other FHIR resources is called
 * [extraction](http://build.fhir.org/ig/HL7/sdc/extraction.html). The reverse process of converting
 * existing FHIR resources to [QuestionnaireResponse] s to be used to pre-fill the UI is called
 * [population](http://build.fhir.org/ig/HL7/sdc/populate.html).
 *
 * [Definition-based extraction](http://build.fhir.org/ig/HL7/sdc/extraction.html#definition-based-extraction)
 * and
 * [expression-based population](http://build.fhir.org/ig/HL7/sdc/populate.html#expression-based-population)
 * are used because these approaches are generic enough to work with any FHIR resource types, and at
 * the same time relatively easy to implement.
 *
 * WARNING: This is not production-ready.
 */
object ResourceMapper {

  /**
   * Extract a FHIR resource from the `questionnaire` and `questionnaireResponse`.
   *
   * This method assumes there is only one FHIR resource to be extracted from the given
   * `questionnaire` and `questionnaireResponse`.
   */
  fun extract(questionnaire: Questionnaire, questionnaireResponse: QuestionnaireResponse): Base {
    val className = questionnaire.itemContextNameToExpressionMap.values.first()
    return (Class.forName("org.hl7.fhir.r4.model.$className").newInstance() as Base).apply {
      extractFields(questionnaire.item, questionnaireResponse.item)
    }
  }

  /**
   * Extracts answer values from [questionnaireResponseItemList] and updates the fields defined in
   * the corresponding questions in [questionnaireItemList]. This method handles nested fields.
   */
  private fun Base.extractFields(
    questionnaireItemList: List<Questionnaire.QuestionnaireItemComponent>,
    questionnaireResponseItemList: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>
  ) {
    val questionnaireItemListIterator = questionnaireItemList.iterator()
    val questionnaireResponseItemListIterator = questionnaireResponseItemList.iterator()
    while (questionnaireItemListIterator.hasNext() &&
      questionnaireResponseItemListIterator.hasNext()) {
      extractField(
        questionnaireItemListIterator.next(),
        questionnaireResponseItemListIterator.next()
      )
    }
  }

  /**
   * Extracts the answer value from [questionnaireResponseItem] and updates the field defined in
   * [questionnaireItem]. This method handles nested fields.
   */
  private fun Base.extractField(
    questionnaireItem: Questionnaire.QuestionnaireItemComponent,
    questionnaireResponseItem: QuestionnaireResponse.QuestionnaireResponseItemComponent
  ) {
    if (questionnaireItem.definition == null) {
      extractFields(questionnaireItem.item, questionnaireResponseItem.item)
      return
    }

    val targetFieldName = questionnaireItem.definitionFieldName ?: return
    if (targetFieldName.isEmpty()) {
      return
    }

    val propertyType = questionnaireItem.inferPropertyResourceClass ?: return
    if (questionnaireItem.type == Questionnaire.QuestionnaireItemType.GROUP) {
      // create a class for questionnaire item of type group and add to the resource
      val base: Base = propertyType.mainType.newInstance() as Base

      base.extractFields(questionnaireItem.item, questionnaireResponseItem.item)

      updateFieldWithAnswer(base, targetFieldName, propertyType)
    } else {
      if (questionnaireResponseItem.answer.isEmpty()) return
      val answer = questionnaireResponseItem.answer.first().value

      if (!propertyType.mainType.isEnum) {
        // this is a low level type e.g. StringType
        updateFieldWithAnswer(answer, targetFieldName, propertyType)
      } else {
        // this is a high level type e.g. AdministrativeGender
        updateFieldWithEnum(propertyType, targetFieldName, answer)
      }
    }
  }
}

/**
 * Updates a field of name [targetFieldName] on this object with the generated enum from [answer]
 * using the declared setter. [propertyType] helps to determine the field class type. The enum is
 * generated by calling fromCode method on the enum class
 */
private fun Base.updateFieldWithEnum(propertyType: Field, targetFieldName: String, answer: Base) {
  /*
  We have a org.hl7.fhir.r4.model.Enumerations class which contains inner classes of code-names and
  re-implements the classes in the org.hl7.fhir.r4.model.codesystems package
  The inner-classes in the Enumerations package are valid and not dependent on the classes in the codesystems package
  All enum classes in the org.hl7.fhir.r4.model package implement the fromCode(), toCode() methods among others
   */
  val dataTypeClass: Class<*> = propertyType.mainType
  val fromCodeMethod: Method = dataTypeClass.getDeclaredMethod("fromCode", String::class.java)

  val stringValue = if (answer is Coding) answer.code else answer.toString()

  javaClass
    .getMethod("set${targetFieldName.capitalize()}", propertyType.mainType)
    .invoke(this, fromCodeMethod.invoke(dataTypeClass, stringValue))
}

/**
 * Updates a field of name [targetFieldName] on this object with the value from [answer] using the
 * declared setter. [fieldType] helps to determine the field class type.
 */
private fun Base.updateFieldWithAnswer(answer: Base, targetFieldName: String, fieldType: Field) {
  val paramAns = generateAnswerWithCorrectType(answer, fieldType)

  try {
    javaClass
      .getMethod("set${targetFieldName.capitalize()}Element", fieldType.setterParamType)
      .invoke(this, paramAns)
  } catch (e: NoSuchMethodException) {
    // some set methods expect a list of objects
    javaClass
      .getMethod("set${targetFieldName.capitalize()}", fieldType.setterParamType)
      .invoke(
        this,
        if (fieldType.isParameterized && fieldType.isList) listOf(paramAns) else paramAns
      )
  }
}

/**
 * This method enables us to perform an extra step to wrap the answer using the correct type. This
 * is useful in cases where a single question maps to a CompositeType such as [CodeableConcept] or
 * enum. Normally, composite types are mapped using group questions which provide direct alignment
 * to the type elements in the group questions
 */
private fun generateAnswerWithCorrectType(answer: Base, fieldType: Field): Base {
  when (fieldType.mainType) {
    CodeableConcept::class.java -> {
      if (answer is Coding) {
        return CodeableConcept(answer).apply { text = answer.display }
      }
    }
    IdType::class.java -> {
      if (answer is StringType) {
        return IdType(answer.value)
      }
    }
  }

  return answer
}

/**
 * Returns the field name for the [Questionnaire.Item]'s definition.
 *
 * For example, if the definition URI is
 * `"http://hl7.org/fhir/StructureDefinition/Patient#Patient.birthDate"`, this function will return
 * `"birthDate"`.
 */
private val Questionnaire.QuestionnaireItemComponent.definitionFieldName
  get() = this.definition?.substringAfterLast(".")

/**
 * Returns the [Class] for the answer to the [Questionnaire.QuestionnaireItemComponent].
 *
 * Used to retrieve the method to invoke to set the field in the extracted FHIR resource.
 */
private fun Questionnaire.QuestionnaireItemType.getClass(): Class<out Base>? =
  when (this) {
    Questionnaire.QuestionnaireItemType.DATE -> DateType::class.java
    Questionnaire.QuestionnaireItemType.BOOLEAN -> BooleanType::class.java
    Questionnaire.QuestionnaireItemType.DECIMAL -> DecimalType::class.java
    Questionnaire.QuestionnaireItemType.INTEGER -> IntegerType::class.java
    Questionnaire.QuestionnaireItemType.DATETIME -> DateTimeType::class.java
    Questionnaire.QuestionnaireItemType.TIME -> TimeType::class.java
    Questionnaire.QuestionnaireItemType.STRING, Questionnaire.QuestionnaireItemType.TEXT ->
      StringType::class.java
    Questionnaire.QuestionnaireItemType.URL -> UrlType::class.java
    else -> null
  }

/**
 * The map from the `name`s to `expression`s in the
 * [item extraction context extension](http://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-itemExtractionContext.html)
 * s.
 */
private val Questionnaire.itemContextNameToExpressionMap: Map<String, String>
  get() {
    return this.extension
      .filter { it.url == ITEM_CONTEXT_EXTENSION_URL }
      .map {
        val expression = it.value as Expression
        expression.name to expression.expression
      }
      .toMap()
  }

/**
 * Extracts a list containing the resource name followed by field names leading to the destination
 * field defined in the [definition] field, or `null` if the [definition] field is empty or invalid.
 *
 * For example, if the [definition] field is
 * "http://hl7.org/fhir/StructureDefinition/Patient#Patient.name", `listOf("Patient", "name")` will
 * be returned.
 */
private val Questionnaire.QuestionnaireItemComponent.targetResourceAndElement: List<String>?
  get() {
    val snapshotPath = definition.substringAfter('#', "")
    if (!"[a-zA-Z]+(\\.[a-zA-Z]+)+".toRegex().matches(snapshotPath)) return null

    return snapshotPath.split(".")
  }

/**
 * Retrieves details about the target field defined in
 * [org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent.definition] for easier searching
 * of the setter methods and converting the answer to the expected parameter type.
 */
private val Questionnaire.QuestionnaireItemComponent.inferPropertyResourceClass: Field?
  get() {
    val modelAndField = targetResourceAndElement ?: return null
    var resourceClass: Class<*> = Class.forName("org.hl7.fhir.r4.model.${modelAndField.get(0)}")
    var resourceField: Field? = null

    modelAndField.forEachIndexed loop@{ index, fieldName ->
      if (index == 0) return@loop

      resourceField = resourceClass.getFieldOrNull(fieldName) ?: return null
      resourceClass = resourceField!!.mainType
    }

    return resourceField
  }

/**
 * See
 * [Extension: item extraction context](http://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-itemExtractionContext.html)
 * .
 */
private const val ITEM_CONTEXT_EXTENSION_URL: String =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemContext"

private val Field.isList: Boolean
  get() = isParameterized && type == List::class.java

private val Field.isParameterized: Boolean
  get() = genericType is ParameterizedType

/* The type of the parameter in the setter for this field. */
private val Field.setterParamType: Class<*>
  get() = type

private fun Field.retrieveNonParameterizedType(): Class<*> =
  (genericType as ParameterizedType).actualTypeArguments[0] as Class<*>

/**
 * The non-parameterized type in this field.
 *
 * For example a Field of List<String> should give [String] as the [mainType]
 */
private val Field.mainType: Class<*>
  get() = if (isParameterized) retrieveNonParameterizedType() else type

private fun Class<*>.getFieldOrNull(name: String): Field? {
  return try {
    getDeclaredField(name)
  } catch (ex: NoSuchFieldException) {
    return null
  }
}
