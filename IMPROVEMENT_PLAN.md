# AGI Assistant 前端改进计划

## 📋 改进总结

### 已完成的改进

#### 1. 设计系统 (Design System)
- ✅ 创建 `styles/variables.css` - CSS 变量系统
  - 品牌色、中性色、功能色
  - 间距系统（4px 基准）
  - 圆角、阴影、字体
  - 过渡动画
  - 暗色主题支持
- ✅ 创建 `styles/animations.css` - 动画系统
  - 页面过渡动画
  - 列表项过渡
  - 骨架屏闪烁
  - 打字指示器
  - 流式光标
  - 减少动画（无障碍支持）

#### 2. 工具函数
- ✅ 创建 `utils/format.ts` - 统一格式化函数
  - `formatRelativeTime()` - 相对时间
  - `formatDateTime()` - 日期时间
  - `formatFileSize()` - 文件大小
  - `formatNumber()` - 数字格式化
  - `formatPercent()` - 百分比
  - `truncateText()` - 文本截断
  - `formatLatency()` - 延迟格式化
  - 状态映射（文档、评测、记忆）
  - `debounce()` / `throttle()` - 防抖节流

#### 3. 主题系统
- ✅ 创建 `composables/useTheme.ts` - 主题切换 Composable
  - 支持亮色/暗色/跟随系统
  - 自动保存到 localStorage
  - 监听系统主题变化
- ✅ 创建 `components/common/ThemeToggle.vue` - 主题切换组件
  - 动画过渡效果
  - 工具提示

#### 4. 骨架屏组件
- ✅ 创建 `components/common/SkeletonLoader.vue`
  - 支持 4 种变体：card、list、chat、basic
  - 自定义行数和宽度

#### 5. 页面改进
- ✅ `App.vue` - 全新侧边栏设计
  - 现代化导航菜单
  - 可折叠侧边栏
  - 路由过渡动画
  - 响应式设计
- ✅ `ChatView.vue` - 对话页面重构
  - 学习 ChatGPT 的简洁设计
  - 欢迎屏幕功能卡片
  - 改进的输入区域
  - 列表过渡动画
- ✅ `DocumentView.vue` - 文档管理页面重构
  - 卡片式文档列表
  - 骨架屏加载
  - 改进的上传区域
  - 响应式设计

---

## 🎯 后续改进计划

### 阶段二：核心组件优化（优先级高）

#### 1. ChatWindow 组件
- [ ] 消息气泡样式优化
- [ ] 代码块语法高亮改进
- [ ] 引用来源展示优化
- [ ] 沙箱结果展示优化
- [ ] 消息操作菜单（复制、重新生成、删除）

#### 2. MessageBubble 组件
- [ ] 用户消息右对齐
- [ ] AI 消息左对齐
- [ ] 头像显示
- [ ] 时间戳显示
- [ ] 消息状态指示器

#### 3. SourceReference 组件
- [ ] 折叠/展开动画
- [ ] 来源高亮显示
- [ ] 相关度评分展示
- [ ] 点击跳转到原文

### 阶段三：记忆和评测页面

#### 1. MemoryView 页面
- [ ] 记忆卡片样式优化
- [ ] 搜索功能改进
- [ ] 类型筛选标签
- [ ] 用户画像展示
- [ ] 记忆图谱可视化（使用 ECharts）

#### 2. EvaluationView 页面
- [ ] 评测任务卡片
- [ ] 指标图表展示（使用 ECharts）
- [ ] 对比结果可视化
- [ ] 导出评测报告

### 阶段四：代码沙箱页面

#### 1. SandboxView 页面
- [ ] Monaco Editor 主题同步
- [ ] 语言选择器改进
- [ ] 输出控制台样式
- [ ] 执行结果展示
- [ ] 代码模板管理

### 阶段五：高级功能

#### 1. 响应式设计完善
- [ ] 移动端导航菜单
- [ ] 触摸手势支持
- [ ] 自适应布局

#### 2. 暗色模式完善
- [ ] 所有组件暗色适配
- [ ] 代码编辑器主题同步
- [ ] 图表暗色主题

#### 3. 无障碍支持
- [ ] ARIA 标签
- [ ] 键盘导航
- [ ] 屏幕阅读器支持

#### 4. 性能优化
- [ ] 虚拟滚动
- [ ] 图片懒加载
- [ ] 代码分割
- [ ] 缓存策略

#### 5. PWA 支持
- [ ] Service Worker
- [ ] 离线支持
- [ ] 安装提示

---

## 📚 学习的优秀设计

### ChatGPT
- ✅ 简洁的侧边栏设计
- ✅ 对话列表管理
- ✅ 输入区域设计
- ✅ 消息气泡样式

### Claude
- ✅ 柔和的色彩系统
- ✅ 优雅的动画效果
- ✅ 清晰的信息层次

### Cursor
- ✅ 代码编辑器集成
- ✅ 快捷键系统
- ✅ 主题切换

---

## 🛠️ 技术栈

### 新增依赖
- 无（使用现有技术栈）

### CSS 变量系统
```css
--color-primary: #6366f1
--color-bg-primary: #ffffff
--color-text-primary: #1a1a2e
--radius-lg: 12px
--shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1)
--transition-normal: 200ms cubic-bezier(0.4, 0, 0.2, 1)
```

---

## 📝 使用指南

### 1. 使用 CSS 变量
```css
.my-component {
  color: var(--color-text-primary);
  background-color: var(--color-bg-primary);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  transition: all var(--transition-normal);
}
```

### 2. 使用动画类
```html
<div class="card-hover">悬浮效果</div>
<div class="skeleton">骨架屏</div>
<div class="spin">旋转加载</div>
```

### 3. 使用格式化函数
```typescript
import { formatFileSize, formatRelativeTime } from '@/utils/format'

formatFileSize(1024) // "1 KB"
formatRelativeTime('2024-01-01') // "3个月前"
```

### 4. 使用主题切换
```typescript
import { useTheme } from '@/composables/useTheme'

const { theme, isDark, toggleTheme } = useTheme()
```

---

## 🎨 设计规范

### 颜色使用
- 主要操作：`--color-primary`
- 成功状态：`--color-success`
- 警告状态：`--color-warning`
- 危险操作：`--color-danger`
- 文本颜色：`--color-text-primary/secondary/tertiary`
- 背景颜色：`--color-bg-primary/secondary/tertiary`

### 间距使用
- 组件内间距：`var(--space-2)` ~ `var(--space-4)`
- 组件间间距：`var(--space-4)` ~ `var(--space-6)`
- 页面内边距：`var(--space-6)` ~ `var(--space-8)`

### 圆角使用
- 按钮、输入框：`var(--radius-md)` (8px)
- 卡片、面板：`var(--radius-lg)` (12px)
- 大容器：`var(--radius-xl)` (16px)
- 头像：`var(--radius-full)` (9999px)

### 阴影使用
- 悬浮效果：`var(--shadow-md)`
- 弹出层：`var(--shadow-lg)`
- 模态框：`var(--shadow-xl)`
