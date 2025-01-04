package com.fina.wheelclass.service

import com.fina.wheelclass.model.EntityInfo
import com.fina.wheelclass.model.EntityField
import com.fina.wheelclass.model.EntityMethod
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlin.math.abs

class EntitySearchService(private val project: Project) {
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

        var totalScore = 0.0
        val maxPossibleScore = selectedColumns.size.toDouble()

        // 计算源字段到目标字段的匹配度
        selectedColumns.forEach { column ->
            val columnScore = when (column) {
                0 -> calculateNameSimilarity(sourceFields, targetFields)
                1 -> calculateTypeSimilarity(sourceFields, targetFields)
                2 -> calculateCommentSimilarity(sourceFields, targetFields)
                else -> 0.0
            }
            totalScore += columnScore
        }

        // 计算最终相似度
        val similarity = totalScore / maxPossibleScore

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
        val sourceMatches = sourceFields.count { sourceField ->
            targetFields.any { targetField ->
                sourceField.type.equals(targetField.type, ignoreCase = true)
            }
        }
        
        val targetMatches = targetFields.count { targetField ->
            sourceFields.any { sourceField ->
                targetField.type.equals(sourceField.type, ignoreCase = true)
            }
        }

        return if (sourceMatches == 0 && targetMatches == 0) 0.0
        else 2.0 * (sourceMatches.toDouble() / sourceFields.size) * (targetMatches.toDouble() / targetFields.size) / 
             ((sourceMatches.toDouble() / sourceFields.size) + (targetMatches.toDouble() / targetFields.size))
    }

    private fun calculateCommentSimilarity(sourceFields: List<EntityField>, targetFields: List<EntityField>): Double {
        if (sourceFields.all { it.comment.isNullOrEmpty() } || targetFields.all { it.comment.isNullOrEmpty() }) {
            return 0.0
        }

        val sourceMatches = sourceFields.count { sourceField ->
            targetFields.any { targetField ->
                if (!sourceField.comment.isNullOrEmpty() && !targetField.comment.isNullOrEmpty()) {
                    val cleanSourceComment = cleanComment(sourceField.comment!!)
                    val cleanTargetComment = cleanComment(targetField.comment!!)
                    cleanSourceComment.contains(cleanTargetComment, ignoreCase = true) ||
                    cleanTargetComment.contains(cleanSourceComment, ignoreCase = true)
                } else false
            }
        }
        
        val targetMatches = targetFields.count { targetField ->
            sourceFields.any { sourceField ->
                if (!sourceField.comment.isNullOrEmpty() && !targetField.comment.isNullOrEmpty()) {
                    val cleanSourceComment = cleanComment(sourceField.comment!!)
                    val cleanTargetComment = cleanComment(targetField.comment!!)
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
        return psiClass.fields.map { field ->
            EntityField(
                name = field.name,
                type = field.type.presentableText,
                comment = field.docComment?.text ?: "",
                annotations = field.annotations.mapNotNull { it.qualifiedName }
            )
        }
    }
}
