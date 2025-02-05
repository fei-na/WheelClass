package com.fina.wheelclass.ui

import com.fina.wheelclass.model.EntityInfo
import com.fina.wheelclass.model.EntityField
import com.fina.wheelclass.service.EntitySearchService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.UIManager
import java.awt.FlowLayout
import java.util.Timer
import java.util.TimerTask
import java.awt.Font
import javax.swing.table.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.editor.ScrollType

class EntitySearchDialog(
    private val project: Project,
    private val sourceClass: PsiClass,
    private val minSimilarity: Double = 0.5
) : DialogWrapper(project, true) {
    private val splitter = JBSplitter(false, 0.3f)
    private val entityTableModel = EntityTableModel()
    private val entitySearchService = EntitySearchService(project, sourceClass)
    private val diffComponent = JPanel(BorderLayout())
    private var searchJob: Timer? = null
    
    // 右侧表格
    private val entityTable = JBTable(entityTableModel).apply {
        setShowGrid(true)
        intercellSpacing = Dimension(1, 1)
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        rowHeight = 25
        
        // 添加自定义渲染器
        setDefaultRenderer(Object::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (entityTableModel.isLoading()) {
                    // 加载状态下的样式
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.BOLD)  // 加粗显示
                } else {
                    // 正常状态下的样式
                    horizontalAlignment = when (column) {
                        2 -> SwingConstants.CENTER  // 相似度居中
                        else -> SwingConstants.LEFT  // 其他左对齐
                    }
                    font = font.deriveFont(Font.PLAIN)
                }
                return component
            }
        })
    }
    
    // 右下表格
    private val selectedClassFieldsTable = JBTable().apply {
        setShowGrid(true)
        intercellSpacing = Dimension(1, 1)
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        rowHeight = 25
    }
    
    // 左侧显示源类的字段表格
    private val sourceFieldsTable = JBTable(SourceFieldsTableModel()).apply {
        setShowGrid(true)
        intercellSpacing = Dimension(1, 1)
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        rowHeight = 25
        
        // 设置表头为复选框
        tableHeader.defaultRenderer = CheckboxHeaderRenderer()
        tableHeader.reorderingAllowed = false
    }
    
    init {
        super.init()
        title = "比 较 类"
        
        // 添加双击事件监听器
        entityTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {  // 双击事件
                    val row = entityTable.selectedRow
                    if (row != -1) {
                        val entity = entityTableModel.getEntityAt(row)
                        openEntityFile(entity)
                    }
                }
            }
        })
        
        // 设置表格引用并初始化选中列
        (sourceFieldsTable.model as SourceFieldsTableModel).apply {
            setUpdateCallback { 
                // 确保在 EDT 中执行更新
                SwingUtilities.invokeLater {
                    val selectedColumns = getSelectedColumns()
                    val currentFields = getFields()
                    performSearch(currentFields, selectedColumns)
                }
            }
            // 默认选中字段名和字段类型列
            toggleColumnSelection(0) // 字段名
            toggleColumnSelection(1) // 字段类型
        }
        
        // 添加表头点击监听
        sourceFieldsTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val table = e.source as JTableHeader
                val columnModel = table.columnModel
                val viewColumn = columnModel.getColumnIndexAtX(e.x)
                if (viewColumn != -1) {
                    (sourceFieldsTable.model as SourceFieldsTableModel).toggleColumnSelection(viewColumn)
                    table.repaint()
                    updateSimilarClasses()
                }
            }
        })
        
        // 加载源类字段
        val sourceFields = entitySearchService.getEntityFields(sourceClass)
        (sourceFieldsTable.model as SourceFieldsTableModel).setFields(sourceFields)
    }

    private fun updateSimilarClasses() {
        val model = sourceFieldsTable.model as SourceFieldsTableModel
        val selectedColumns = model.getSelectedColumns()
        val currentFields = model.getFields()
        performSearch(currentFields, selectedColumns)
    }

    private fun performSearch(fields: List<EntityField>, selectedColumns: List<Int>) {
        searchJob?.cancel()
        searchJob = Timer()
        
        // 设置加载状态
        entityTableModel.setLoading(true)
        
        searchJob?.schedule(object : TimerTask() {
            override fun run() {
                SwingUtilities.invokeLater {
                    if (selectedColumns.isNotEmpty() && fields.isNotEmpty()) {
                        //println("执行搜索: 字段数=${fields.size}, 选中列=${selectedColumns}")
                        val similarClasses = entitySearchService.findSimilarEntities(
                            fields,
                            minSimilarity,
                            selectedColumns
                        )
                        entityTableModel.updateData(similarClasses)
                    } else {
                        entityTableModel.updateData(emptyList())
                    }
                }
            }
        }, 300)
    }

    override fun createCenterPanel(): JComponent {
        val dimension = Dimension(1000, 800)
        
        // 修改按钮面板，增加批量删除功能
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("删除选中数据").apply {
                addActionListener {
                    val selectedRows = sourceFieldsTable.selectedRows
                    if (selectedRows.isNotEmpty()) {
                        // 从大到小排序，以便从后向前删除
                        selectedRows.sortedDescending().forEach { row ->
                            (sourceFieldsTable.model as SourceFieldsTableModel).removeField(row)
                        }
                    }
                }
            })
            add(JButton("清空全部").apply {
                addActionListener {
                    (sourceFieldsTable.model as SourceFieldsTableModel).removeAllFields()
                }
            })
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            //add(JLabel("Source Class Fields (Select columns to compare):"), BorderLayout.NORTH)
            add(JLabel(" 原始类 :"), BorderLayout.NORTH)
            add(JBScrollPane(sourceFieldsTable), BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
            preferredSize = Dimension(300, dimension.height)
        }
        
        // 初始化右侧面板：上方显示相似类列表，下方显示选中类的字段
        val rightPanel = JBSplitter(true, 0.5f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(JLabel(" 类似类 :"), BorderLayout.NORTH)
                add(JBScrollPane(entityTable), BorderLayout.CENTER)
            }
            secondComponent = JPanel(BorderLayout()).apply {
                add(JLabel(" 所选类的字段 :"), BorderLayout.NORTH)
                add(JBScrollPane(selectedClassFieldsTable), BorderLayout.CENTER)
            }
            preferredSize = Dimension(700, dimension.height)
        }

        // 监听实体表格选择
        entityTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = entityTable.selectedRow
                if (selectedRow != -1) {
                    val entity = entityTableModel.getEntityAt(selectedRow)
                    updateSelectedClassFields(entity)
                }
            }
        }

        return JPanel(BorderLayout()).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.CENTER)
            preferredSize = dimension
        }
    }

    private fun updateSelectedClassFields(entity: EntityInfo) {
        // 创建新的 DefaultTableModel，移除注解列
        val model = DefaultTableModel(
            emptyArray<Array<Any>>(),
            arrayOf("字段名", "字段类型", "备注") // 移除注解列
        )
        
        selectedClassFieldsTable.model = model
        
        // 添加数据行，移除注解数据
        entity.fields.forEach { field ->
            model.addRow(arrayOf<Any>(
                field.name,
                field.type,
                field.comment.let { cleanComment(it) }
            ))
        }
    }

    // 添加清理注释的辅助方法
    private fun cleanComment(comment: String): String {
        return comment.replace(Regex("[/*]+"), "").trim()
    }

    override fun dispose() {
        searchJob?.cancel()
        super.dispose()
    }

    override fun createSouthPanel(): JComponent? {
        return null  // 返回 null 来移除底部按钮面板
    }

    // 添加打开文件的方法
    private fun openEntityFile(entity: EntityInfo) {
        val project = this.project
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        
        // 通过完整类名查找对应的类文件
        val qualifiedName = "${entity.packageName}.${entity.className}"
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(qualifiedName, scope)
        
        if (psiClass != null) {
            // 获取对应的虚拟文件
            val virtualFile = psiClass.containingFile?.virtualFile
            if (virtualFile != null) {
                // 打开文件并定位到类定义处
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                
                // 获取编辑器并移动光标到类定义处
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    val offset = psiClass.textOffset
                    editor.caretModel.moveToOffset(offset)
                    editor.selectionModel.removeSelection()
                    
                    // 确保光标可见
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
        }
    }
}

// 源类字段表格模型
private class SourceFieldsTableModel : AbstractTableModel() {
    private val columnNames = arrayOf("字段名", "字段类型", "备注")
    private var fields = mutableListOf<EntityField>()
    private val selectedColumns = mutableSetOf<Int>()
    private var hasEmptyRow = true
    private var updateCallback: (() -> Unit)? = null

    fun setUpdateCallback(callback: () -> Unit) {
        updateCallback = callback
    }

    override fun getColumnCount() = columnNames.size
    override fun getRowCount() = fields.size + (if (hasEmptyRow) 1 else 0)
    
    override fun getValueAt(row: Int, col: Int): Any? {
        if (row >= fields.size) return ""
        
        val field = fields[row]
        return when (col) {
            0 -> field.name
            1 -> field.type
            2 -> field.comment.let { cleanComment(it) }
            else -> null
        }
    }

    override fun getColumnName(column: Int) = columnNames[column]

    override fun isCellEditable(row: Int, col: Int): Boolean = col != 3

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        val strValue = value?.toString() ?: ""
        
        if (row >= fields.size) {
            if (strValue.isNotEmpty()) {
                val newField = when (col) {
                    0 -> EntityField(strValue, "String", "", emptyList())
                    1 -> EntityField("newField", strValue, "", emptyList())
                    2 -> EntityField("newField", "String", strValue, emptyList())
                    else -> return
                }
                fields.add(newField)
                fireTableRowsInserted(fields.size - 1, fields.size - 1)
                updateCallback?.invoke()
            }
        } else {
            val field = fields[row]
            val updatedField = when (col) {
                0 -> field.copy(name = strValue)
                1 -> field.copy(type = strValue)
                2 -> field.copy(comment = strValue)
                else -> return
            }
            fields[row] = updatedField
            fireTableCellUpdated(row, col)
            updateCallback?.invoke()
        }
    }

    fun removeField(row: Int) {
        if (row in fields.indices) {
            fields.removeAt(row)
            fireTableRowsDeleted(row, row)
            // 确保在删除后更新数据
            SwingUtilities.invokeLater {
                updateCallback?.invoke()
            }
        }
    }

    fun setFields(newFields: List<EntityField>) {
        fields.clear()
        fields.addAll(newFields)
        fireTableDataChanged()
        // 确保在设置新数据后更新
        SwingUtilities.invokeLater {
            updateCallback?.invoke()
        }
    }

    fun getSelectedColumns(): List<Int> = selectedColumns.toList()

    fun isColumnSelected(column: Int): Boolean = column in selectedColumns

    fun toggleColumnSelection(column: Int) {
        if (column in selectedColumns) {
            selectedColumns.remove(column)
        } else {
            selectedColumns.add(column)
        }
        fireTableStructureChanged()
    }

    // 清理注释内容，移除 / 和 * 字符
    private fun cleanComment(comment: String): String {
        return comment.replace(Regex("[/*]+"), "").trim()
    }

    fun removeAllFields() {
        val size = fields.size
        if (size > 0) {
            fields.clear()
            fireTableRowsDeleted(0, size - 1)
            // 确保在删除后更新数据
            SwingUtilities.invokeLater {
                updateCallback?.invoke()
            }
        }
    }

    fun getFields(): List<EntityField> = fields.toList()
}

// 实体表格模型
private class EntityTableModel : AbstractTableModel() {
    private val columnNames = arrayOf("类名", "包名", "相似度")
    private var data = mutableListOf<EntityInfo>()
    private var isLoading = true

    override fun getRowCount() = if (isLoading || data.isEmpty()) 1 else data.size
    override fun getColumnCount() = columnNames.size

    override fun getValueAt(row: Int, col: Int): Any? {
        if (isLoading) {
            // 只在中间列显示"查询中..."
            return when (col) {
                1 -> "查询中..."
                else -> ""
            }
        }
        
        if (data.isEmpty()) {
            return ""
        }

        val entity = data[row]
        return when (col) {
            0 -> entity.className
            1 -> entity.packageName
            2 -> "${(entity.similarity * 100).toInt()}%"
            else -> null
        }
    }

    override fun getColumnName(column: Int) = columnNames[column]

    // 自定义单元格渲染器，用于居中显示
    fun getLoadingRenderer(): TableCellRenderer {
        return DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        fireTableDataChanged()
    }

    fun updateData(newData: List<EntityInfo>) {
        isLoading = false
        data.clear()
        data.addAll(newData)
        fireTableDataChanged()
    }

    fun getEntityAt(row: Int): EntityInfo = data[row]

    fun isLoading() = isLoading
}

// 自定义表头渲染器，支持复选框
private class CheckboxHeaderRenderer : JCheckBox(), TableCellRenderer {
    init {
        horizontalAlignment = CENTER
        border = UIManager.getBorder("TableHeader.cellBorder")
        background = UIManager.getColor("TableHeader.background")
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val model = table.model as? SourceFieldsTableModel
        setSelected(model?.isColumnSelected(column) ?: false)
        text = value?.toString() ?: ""
        return this
    }
}
