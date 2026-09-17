<template>
  <div class="login-view">
    <div class="login-card">
      <div class="brand">
        <div class="brand-icon">
          <el-icon :size="30"><ChatDotRound /></el-icon>
        </div>
        <h1>AGI Assistant</h1>
        <p>{{ mode === 'login' ? '登录以继续使用' : '创建你的账号' }}</p>
      </div>

      <el-radio-group v-model="mode" class="mode-switch" @change="handleModeChange">
        <el-radio-button label="login">登录</el-radio-button>
        <el-radio-button label="register">注册</el-radio-button>
      </el-radio-group>

      <el-alert
        v-if="errorMsg"
        :title="errorMsg"
        type="error"
        :closable="false"
        show-icon
        class="error-alert"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="activeRules"
        label-position="top"
        class="login-form"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            clearable
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="昵称（可选）" prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称，留空则与用户名相同" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入密码"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="邮箱（可选）" prop="email">
          <el-input v-model="form.email" placeholder="name@example.com" clearable />
        </el-form-item>

        <el-button
          type="primary"
          class="submit-btn"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <p class="hint">
        {{ mode === 'login' ? '还没有账号？' : '已有账号？' }}
        <a @click="handleModeChange(mode === 'login' ? 'register' : 'login')">
          {{ mode === 'login' ? '立即注册' : '去登录' }}
        </a>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/** 'login' | 'register' */
const mode = ref('login')
const submitting = ref(false)
const errorMsg = ref('')
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  email: '',
})

/** 校验规则随登录 / 注册切换（注册更严格，与后端 RegisterRequest 约束一致）。 */
const activeRules = computed<FormRules>(() => {
  if (mode.value === 'register') {
    return {
      username: [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { min: 3, max: 64, message: '用户名长度需在 3~64 之间', trigger: 'blur' },
        {
          pattern: /^[A-Za-z0-9_.-]+$/,
          message: '用户名只能包含字母、数字、下划线、点与短横线',
          trigger: 'blur',
        },
      ],
      password: [
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 8, max: 128, message: '密码长度需在 8~128 之间', trigger: 'blur' },
      ],
      confirmPassword: [
        { required: true, message: '请再次输入密码', trigger: 'blur' },
        {
          validator: (_rule: any, value: any, callback: any) => {
            if (value !== form.password) {
              callback(new Error('两次输入的密码不一致'))
            } else {
              callback()
            }
          },
          trigger: 'blur',
        },
      ],
      email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
    }
  }
  return {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  }
})

function handleModeChange(next: string | number | boolean): void {
  mode.value = String(next)
  errorMsg.value = ''
  formRef.value?.clearValidate()
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  errorMsg.value = ''

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (mode.value === 'login') {
      await authStore.login({ username: form.username.trim(), password: form.password })
    } else {
      await authStore.register({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || undefined,
        email: form.email.trim() || undefined,
      })
      ElMessage.success('注册成功，已自动登录')
    }

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error: any) {
    // 凭据类接口的错误不走全局 toast（见 utils/request.ts），在此就地展示
    errorMsg.value =
      error?.response?.data?.message || error?.message || '操作失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-view {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--space-6);
  background: linear-gradient(135deg, var(--color-bg-secondary), var(--color-primary-bg));
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: var(--space-8);
  background-color: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-xl);
}

.brand {
  text-align: center;
  margin-bottom: var(--space-6);
}

.brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-light));
  color: #fff;
  margin-bottom: var(--space-4);
}

.brand h1 {
  margin: 0 0 var(--space-1);
  font-size: var(--text-2xl);
  font-weight: 700;
  color: var(--color-text-primary);
}

.brand p {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

.mode-switch {
  display: flex;
  width: 100%;
  margin-bottom: var(--space-5);
}

.mode-switch :deep(.el-radio-button) {
  flex: 1;
}

.mode-switch :deep(.el-radio-button__inner) {
  width: 100%;
}

.error-alert {
  margin-bottom: var(--space-4);
}

.login-form :deep(.el-form-item) {
  margin-bottom: var(--space-4);
}

.submit-btn {
  width: 100%;
  margin-top: var(--space-2);
}

.hint {
  margin: var(--space-4) 0 0;
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.hint a {
  cursor: pointer;
  font-weight: 500;
}
</style>
