package com.fina.wheelclass.service

import com.fina.wheelclass.model.EntityInfo
import com.fina.wheelclass.model.EntityField
import com.fina.wheelclass.model.EntityMethod
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlin.math.abs

class EntitySearchService(
    private val project: Project,
    private val sourceClass: PsiClass
) {
    // 缓存已经查找到的类
    private var cachedClasses: List<PsiClass>? = null
    
    fun findSimilarEntities(
        sourceFields: List<EntityField>,  // 修改参数，直接接收字段列表
        minSimilarity: Double,
        selectedColumns: List<Int>
    ): List<EntityInfo> {
        // 延迟初始化缓存
        if (cachedClasses == null) {
            val scope = GlobalSearchScope.projectScope(project)
            cachedClasses = PsiShortNamesCache.getInstance(project)
                .allClassNames
                .flatMap { className ->
                    PsiShortNamesCache.getInstance(project)
                        .getClassesByName(className, scope)
                        .filter { hasDataAnnotation(it) }
                }
        }

        return cachedClasses!!
            .asSequence()
            .filter { psiClass -> 
                // 排除当前类
                psiClass.qualifiedName != sourceClass.qualifiedName
            }
            .map { psiClass ->
                val targetFields = getEntityFields(psiClass)
                val similarity = calculateFieldsSimilarity(sourceFields, targetFields, selectedColumns)
                
                if (similarity >= minSimilarity) {
                    EntityInfo(
                        className = psiClass.name ?: "",
                        packageName = psiClass.qualifiedName?.substringBeforeLast('.') ?: "",
                        fields = targetFields,
                        methods = emptyList(),
                        similarity = similarity,
                        text = psiClass.containingFile?.text
                    )
                } else null
            }
            .filterNotNull()
            .sortedByDescending { it.similarity }
            .toList()
    }

    private fun hasDataAnnotation(psiClass: PsiClass): Boolean {
        return psiClass.modifierList?.annotations?.any {
            it.qualifiedName == "lombok.Data"
        } ?: false
    }

    fun calculateFieldsSimilarity(
        sourceFields: List<EntityField>,
        targetFields: List<EntityField>,
        selectedColumns: List<Int>
    ): Double {
        if (sourceFields.isEmpty() || targetFields.isEmpty() || selectedColumns.isEmpty()) {
            return 0.0
        }

        // 设置权重
        val weights = mapOf(
            0 to 0.7,  // 字段名权重 70%
            1 to 0.3,  // 字段类型权重 30%
            2 to 0.7   // 备注权重 70%
        )

        var weightedScore = 0.0
        var totalWeight = 0.0

        // 计算加权得分
        selectedColumns.forEach { column ->
            val weight = weights[column] ?: 0.0
            val columnScore = when (column) {
                0 -> calculateNameSimilarity(sourceFields, targetFields)
                1 -> calculateTypeSimilarity(sourceFields, targetFields)
                2 -> calculateCommentSimilarity(sourceFields, targetFields)
                else -> 0.0
            }
            weightedScore += columnScore * weight
            totalWeight += weight
        }

        // 计算最终加权相似度
        val similarity = if (totalWeight > 0) weightedScore / totalWeight else 0.0

        // 应用字段数量惩罚因子
        val fieldCountDiff = abs(sourceFields.size - targetFields.size)
        val fieldCountPenalty = 1.0 - (fieldCountDiff.toDouble() / maxOf(sourceFields.size, targetFields.size))
        
        return similarity * fieldCountPenalty
    }

    private fun calculateNameSimilarity(sourceFields: List<EntityField>, targetFields: List<EntityField>): Double {
        val sourceMatches = sourceFields.count { sourceField ->
            targetFields.any { targetField ->
                sourceField.name.equals(targetField.name, ignoreCase = true)
            }
        }
        
        val targetMatches = targetFields.count { targetField ->
            sourceFields.any { sourceField ->
                targetField.name.equals(sourceField.name, ignoreCase = true)
            }
        }

        // 取两个方向匹配度的调和平均数
        return if (sourceMatches == 0 && targetMatches == 0) 0.0
        else 2.0 * (sourceMatches.toDouble() / sourceFields.size) * (targetMatches.toDouble() / targetFields.size) / 
             ((sourceMatches.toDouble() / sourceFields.size) + (targetMatches.toDouble() / targetFields.size))
    }

    private fun calculateTypeSimilarity(sourceFields: List<EntityField>, targetFields: List<EntityField>): Double {
        // 统计源字段中各类型的数量
        val sourceTypeCounts = sourceFields
            .groupBy { it.type.lowercase() }
            .mapValues { it.value.size }
        
        // 统计目标字段中各类型的数量
        val targetTypeCounts = targetFields
            .groupBy { it.type.lowercase() }
            .mapValues { it.value.size }
        
        // 计算类型匹配度
        var matchedTypeCount = 0.0
        sourceTypeCounts.forEach { (type, sourceCount) ->
            val targetCount = targetTypeCounts[type] ?: 0
            // 取两者中较小的数作为匹配数
            matchedTypeCount += minOf(sourceCount, targetCount)
        }
        
        // 计算相似度：匹配的类型数量 / 总字段数
        val sourceTotalCount = sourceFields.size.toDouble()
        val targetTotalCount = targetFields.size.toDouble()
        
        return if (sourceTotalCount == 0.0 || targetTotalCount == 0.0) 0.0
        else 2.0 * matchedTypeCount / (sourceTotalCount + targetTotalCount)
    }

    private fun calculateCommentSimilarity(sourceFields: List<EntityField>, targetFields: List<EntityField>): Double {
        if (sourceFields.all { it.comment.isEmpty() } || targetFields.all { it.comment.isEmpty() }) {
            return 0.0
        }

        val sourceMatches = sourceFields.count { sourceField ->
            targetFields.any { targetField ->
                if (sourceField.comment.isNotEmpty() && targetField.comment.isNotEmpty()) {
                    val cleanSourceComment = cleanComment(sourceField.comment)
                    val cleanTargetComment = cleanComment(targetField.comment)
                    cleanSourceComment.contains(cleanTargetComment, ignoreCase = true) ||
                    cleanTargetComment.contains(cleanSourceComment, ignoreCase = true)
                } else false
            }
        }
        
        val targetMatches = targetFields.count { targetField ->
            sourceFields.any { sourceField ->
                if (sourceField.comment.isNotEmpty() && targetField.comment.isNotEmpty()) {
                    val cleanSourceComment = cleanComment(sourceField.comment)
                    val cleanTargetComment = cleanComment(targetField.comment)
                    cleanSourceComment.contains(cleanTargetComment, ignoreCase = true) ||
                    cleanTargetComment.contains(cleanSourceComment, ignoreCase = true)
                } else false
            }
        }

        return if (sourceMatches == 0 && targetMatches == 0) 0.0
        else 2.0 * (sourceMatches.toDouble() / sourceFields.size) * (targetMatches.toDouble() / targetFields.size) / 
             ((sourceMatches.toDouble() / sourceFields.size) + (targetMatches.toDouble() / targetFields.size))
    }

    private fun cleanComment(comment: String): String {
        return comment.replace(Regex("[/*]+"), "").trim()
    }

    private fun calculateAnnotationSimilarity(sourceFields: List<EntityField>, targetFields: List<EntityField>): Double {
        if (sourceFields.all { it.annotations.isEmpty() } || targetFields.all { it.annotations.isEmpty() }) {
            return 0.0
        }

        val matchCount = sourceFields.count { sourceField ->
            targetFields.any { targetField ->
                val sourceValues = extractAnnotationValues(sourceField.annotations)
                val targetValues = extractAnnotationValues(targetField.annotations)
                sourceValues.any { sourceValue ->
                    targetValues.any { targetValue ->
                        sourceValue.contains(targetValue, ignoreCase = true) ||
                        targetValue.contains(sourceValue, ignoreCase = true)
                    }
                }
            }
        }
        return matchCount.toDouble() / sourceFields.size
    }

    private fun extractAnnotationValues(annotations: List<String>): List<String> {
        return annotations.mapNotNull { annotation ->
            when {
                annotation.startsWith("@ApiModelProperty") -> {
                    Regex("""@ApiModelProperty\s*\(\s*["'](.+?)["']\s*\)""")
                        .find(annotation)?.groupValues?.get(1)
                }
                // 可以添加其他注解的处理
                else -> null
            }
        }
    }

    public fun getEntityFields(psiClass: PsiClass): List<EntityField> {
        val allFields = mutableListOf<EntityField>()
        
        // 获取当前类的字段
        allFields.addAll(psiClass.fields.map { field ->
            EntityField(
                name = field.name,
                type = field.type.presentableText,
                comment = field.docComment?.text ?: "",
                annotations = field.annotations.mapNotNull { it.qualifiedName }
            )
        })
        
        // 递归获取所有父类的字段
        var currentClass = psiClass.superClass
        while (currentClass != null && !currentClass.qualifiedName.equals("java.lang.Object")) {
            allFields.addAll(currentClass.fields.map { field ->
                EntityField(
                    name = field.name,
                    type = field.type.presentableText,
                    comment = field.docComment?.text ?: "",
                    annotations = field.annotations.mapNotNull { it.qualifiedName }
                )
            })
            currentClass = currentClass.superClass
        }
        
        return allFields
    }
}
