package com.fina.wheelclass.model

import com.fina.wheelclass.model.EntityField
import com.fina.wheelclass.model.EntityMethod

data class EntityInfo(
    val className: String,
    val packageName: String,
    val fields: List<EntityField>,
    val methods: List<EntityMethod>,
    var similarity: Double,
    val text: String? = null
)

data class EntityField(
    val name: String,
    val type: String,
    val comment: String = "",
    val annotations: List<String> = emptyList()
)

data class EntityMethod(
    val name: String,
    val returnType: String,
    val parameters: List<MethodParameter>
)

data class MethodParameter(
    val name: String,
    val type: String
) 