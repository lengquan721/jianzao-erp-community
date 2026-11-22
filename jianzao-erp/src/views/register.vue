<template>
  <div class="register">
    <section class="register-intro">
      <div class="brand">
        <span class="brand-mark"></span>
        <span class="brand-name">{{ title }}</span>
      </div>
      <div class="intro-copy">
        <h1 class="headline">{{ proxy.$t('login.headline') }}</h1>
        <p class="subhead">{{ proxy.$t('login.subhead') }}</p>
      </div>
      <p class="register-footer">{{ proxy.$t('login.copyright') }}</p>
      <!-- 商业推广未经部署方明确授权，不渲染对外入口；保留上游翻译但不据此推定授权。 -->
    </section>

    <section class="register-panel">
      <t-form ref="registerRef" :data="registerForm" :rules="registerRules" class="register-form" label-align="top">
        <div class="form-head">
          <h2 class="form-title">{{ proxy.$t('register.register') }}</h2>
          <lang-select class="form-lang" />
        </div>
        <t-form-item v-if="tenantEnabled" name="tenantId" class="form-item">
          <t-select
            v-model="registerForm.tenantId"
            filterable
            :placeholder="proxy.$t('register.selectPlaceholder')"
            :options="tenantOptions"
            size="large"
          >
            <template #prefix-icon><svg-icon icon-class="company" class="input-icon" /></template>
          </t-select>
        </t-form-item>
        <t-form-item name="username" class="form-item">
          <t-input v-model="registerForm.username" size="large" autocomplete="off" :placeholder="proxy.$t('register.username')">
            <template #prefix-icon><svg-icon icon-class="user" class="input-icon" /></template>
          </t-input>
        </t-form-item>
        <t-form-item name="password" class="form-item">
          <t-input
            v-model="registerForm.password"
            type="password"
            size="large"
            autocomplete="off"
            :placeholder="proxy.$t('register.password')"
            @enter="handleRegister"
          >
            <template #prefix-icon><svg-icon icon-class="password" class="input-icon" /></template>
          </t-input>
        </t-form-item>
        <t-form-item name="confirmPassword" class="form-item">
          <t-input
            v-model="registerForm.confirmPassword"
            type="password"
            size="large"
            autocomplete="off"
            :placeholder="proxy.$t('register.confirmPassword')"
            @enter="handleRegister"
          >
            <template #prefix-icon><svg-icon icon-class="password" class="input-icon" /></template>
          </t-input>
        </t-form-item>
        <t-form-item v-if="captchaEnabled" name="code" class="form-item">
          <button type="button" class="slider-trigger" :class="{ 'is-verified': sliderVerified }" @click="openSlider">
            <span class="slider-trigger-icon">
              <svg-icon :icon-class="sliderVerified ? 'checkbox' : 'slider'" />
            </span>
            <span class="slider-trigger-text">{{ sliderVerified ? proxy.$t('slider.verified') : proxy.$t('slider.trigger') }}</span>
            <span v-if="sliderVerified" class="slider-trigger-refresh" @click.stop="openSlider">{{ proxy.$t('slider.refresh') }}</span>
          </button>
          <slider-captcha ref="sliderRef" class="slider-pop" @success="onSliderSuccess" @close="onSliderClose" />
        </t-form-item>
        <t-form-item class="form-item submit-item">
          <t-button :loading="loading" size="large" theme="primary" block @click.prevent="handleRegister">
            <span v-if="!loading">{{ proxy.$t('register.register') }}</span>
            <span v-else>{{ proxy.$t('register.registering') }}</span>
          </t-button>
        </t-form-item>
        <div class="form-row">
          <router-link class="link-type" :to="'/login'">{{ proxy.$t('register.switchLoginPage') }}</router-link>
        </div>
      </t-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { getCodeImg, register, getTenantList } from '@/api/login';
import { RegisterForm, TenantVO } from '@/api/types';
import SliderCaptcha from '@/components/SliderCaptcha/index.vue';
import { to } from 'await-to-js';
import { useI18n } from 'vue-i18n';
import { DialogPlugin, MessagePlugin } from 'tdesign-vue-next';
import type { DialogInstance, TNode } from 'tdesign-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const title = import.meta.env.VITE_APP_TITLE;
const router = useRouter();
const { t } = useI18n();

const registerForm = ref<RegisterForm>({
  tenantId: '',
  username: '',
  password: '',
  confirmPassword: '',
  code: '',
  uuid: '',
  userType: 'sys_user'
});

// 租户开关
const tenantEnabled = ref(true);
// 滑块是否通过服务端校验
const sliderVerified = ref(false);

/** 确认密码一致性校验：t-form 的 validator 返回 {result,message}，替代 EP 的 callback 契约 */
const equalToPassword = (val: any) => {
  if (registerForm.value.password !== val) {
    return { result: false, message: t('register.rule.confirmPassword.equalToPassword') };
  }
  return { result: true, message: '' };
};

/**
 * t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，用宽松类型声明。
 * code（滑块票据）只在滑块校验通过后才加入规则，避免页面加载即报「请完成安全验证」。
 */
const registerRules = computed<Record<string, any>>(() => {
  const rules: Record<string, any> = {
    tenantId: [{ required: true, trigger: 'blur', message: t('register.rule.tenantId.required') }],
    username: [
      { required: true, trigger: 'blur', message: t('register.rule.username.required') },
      { min: 2, max: 20, message: t('register.rule.username.length', { min: 2, max: 20 }), trigger: 'blur' }
    ],
    password: [
      { required: true, trigger: 'blur', message: t('register.rule.password.required') },
      { min: 5, max: 20, message: t('register.rule.password.length', { min: 5, max: 20 }), trigger: 'blur' },
      { pattern: /^[^<>"'|\\]+$/, message: t('register.rule.password.pattern', { strings: '< > " \' \\ |' }), trigger: 'blur' }
    ],
    confirmPassword: [
      { required: true, trigger: 'blur', message: t('register.rule.confirmPassword.required') },
      { validator: equalToPassword, trigger: 'blur' }
    ]
  };
  if (sliderVerified.value) {
    rules.code = [{ required: true, trigger: 'change', message: t('register.rule.code.required') }];
  }
  return rules;
});

const loading = ref(false);
const captchaEnabled = ref(true);
const registerRef = ref<any>();
const sliderRef = ref<InstanceType<typeof SliderCaptcha>>();
// 租户列表
const tenantList = ref<TenantVO[]>([]);

/** t-select 用 :options 的 {label,value} 数组，租户下拉由后端列表映射（DOC/35 §3） */
const tenantOptions = computed(() => tenantList.value.map((item) => ({ label: item.companyName, value: item.tenantId })));

/** 打开滑块：每次注册或刷新都必须重新走服务端校验，旧票据立即作废 */
const openSlider = () => {
  registerForm.value.code = '';
  registerForm.value.uuid = '';
  sliderVerified.value = false;
  sliderRef.value?.open();
};

/** 滑块校验通过：把服务端签发的一次性票据回填到注册表单 */
const onSliderSuccess = ({ uuid, code }: { uuid: string; code: string }) => {
  registerForm.value.uuid = uuid;
  registerForm.value.code = code;
  sliderVerified.value = true;
};

/** 用户关闭滑块：保持未验证状态 */
const onSliderClose = () => {
  sliderVerified.value = false;
};

/** 注册：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const handleRegister = async () => {
  const result = await registerRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  // 滑块未通过时先引导完成验证，不发起注册请求
  if (captchaEnabled.value && !sliderVerified.value) {
    MessagePlugin.warning(t('slider.pending'));
    openSlider();
    return;
  }
  loading.value = true;
  const [err] = await to(register(registerForm.value));
  if (!err) {
    const username = registerForm.value.username;
    // 注册成功提示属 C 层运行期弹层：用 DialogPlugin 包装成 Promise，确认后才跳转登录页。
    await new Promise<void>((resolve) => {
      // 以文本 VNode 插入动态用户名，保留红色提示但不解析为 HTML。
      const body: TNode = (h) => h('div', [h('span', { style: { color: 'red' } }, t('register.registerSuccess', { username }))]);
      let dismissed = false;
      // 确定会连带触发 onConfirm 与 onClose，用标记保证只销毁一次、resolve 只结算一次
      const dismiss = () => {
        if (dismissed) return;
        dismissed = true;
        ins.hide();
        ins.destroy();
        resolve();
      };
      const ins: DialogInstance = DialogPlugin.alert({
        attach: 'body',
        header: '系统提示',
        theme: 'success',
        confirmBtn: '确定',
        body,
        onConfirm: dismiss,
        onClose: dismiss
      });
    });
    await router.push('/login');
  } else {
    loading.value = false;
    // 注册失败后票据已被服务端消费，必须重新滑动
    openSlider();
  }
};

/** 读取验证码开关：滑块验证器由 /auth/code/slider 自行生成拼图，此处只判断开关 */
const getCode = async () => {
  const { data } = await getCodeImg();
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
};

const initTenantList = async () => {
  const { data } = await getTenantList(false);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    tenantList.value = data.voList;
    if (tenantList.value != null && tenantList.value.length !== 0) {
      registerForm.value.tenantId = tenantList.value[0].tenantId;
    }
  }
};

onMounted(() => {
  getCode();
  initTenantList();
});
</script>

<style lang="scss" scoped>
.register {
  --ink-bg: #111827;
  --ink-surface: #171f2c;
  --ink-line: rgba(238, 240, 245, 0.1);
  --ink-text: #eef0f5;
  --ink-secondary: #a3a9b8;
  --ink-muted: #7a8296;
  --accent: #2c5d8f;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 100%;
  background: var(--ink-bg);
  color: var(--ink-text);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.register-intro {
  display: flex;
  flex-direction: column;
  padding: 48px 64px 32px 64px;
  border-right: 1px solid var(--ink-line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-mark {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  background: var(--accent);
}

.brand-name {
  font-size: 16px;
  font-weight: 500;
}

.intro-copy {
  margin: 96px 0 48px 0;
}

.headline {
  margin: 0;
  max-width: 9ch;
  font-size: 40px;
  line-height: 1.25;
  font-weight: 500;
}

.subhead {
  margin: 20px 0 0 0;
  max-width: 36ch;
  font-size: 15px;
  line-height: 1.7;
  color: var(--ink-secondary);
}

.register-footer {
  margin: auto 0 0 0;
  padding-top: 32px;
  color: var(--ink-muted);
  font-size: 12px;
}

.register-commercial {
  margin-top: 6px;
  padding-top: 0;
}

.register-commercial a {
  color: var(--td-brand-color);
  text-decoration: none;
}

.register-commercial a:hover {
  text-decoration: underline;
}

.register-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
}

.register-form {
  width: min(360px, 100%);
  /* t-form 自带 color: rgba(0,0,0,.9)，深色背景下必须显式覆盖 */
  color: var(--ink-text);
  /* 局部覆盖 TDesign 语义色：深底上默认的红色提示偏暗，换成更亮的一档 */
  --td-error-color: #e59191;
  --td-error-color-focus: rgba(229, 145, 145, 0.24);
}

.register-form :deep(.t-form__item),
.register-form :deep(.t-form__label),
.register-form :deep(.t-checkbox),
.register-form :deep(.t-checkbox__label) {
  color: var(--ink-text);
}

.register-form :deep(.t-form__status),
.register-form :deep(.t-input__extra) {
  color: var(--td-error-color);
}

.form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 32px;
}

.form-title {
  margin: 0;
  font-size: 24px;
  font-weight: 500;
}

.form-lang {
  :deep(.lang-select--style) {
    font-size: 18px;
    line-height: 1;
    color: var(--ink-secondary);
  }
}

.form-item {
  margin-bottom: 20px;
}

.register-form :deep(.t-input),
.register-form :deep(.t-select) {
  height: 44px;
  background: var(--ink-surface);
  border-color: var(--ink-line);
  color: var(--ink-text);
}

.register-form :deep(.t-input__inner) {
  color: var(--ink-text);
}

.register-form :deep(.t-input--focused) {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(44, 93, 143, 0.24);
}

.register-form :deep(.t-form__item:has(.slider-trigger)) {
  position: relative;
}

.input-icon {
  width: 16px;
  height: 16px;
  color: var(--ink-muted);
}

.slider-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--ink-line);
  border-radius: 8px;
  background: var(--ink-surface);
  color: var(--ink-secondary);
  font-size: 14px;
  cursor: pointer;
  transition:
    border-color 150ms ease,
    color 150ms ease;
}

.slider-trigger:hover {
  border-color: var(--accent);
}

.slider-trigger.is-verified {
  border-color: rgba(44, 93, 143, 0.5);
  color: var(--ink-text);
}

.slider-trigger-icon {
  display: inline-flex;
  width: 16px;
  height: 16px;
  color: var(--ink-muted);
}

.slider-trigger.is-verified .slider-trigger-icon {
  color: #7fb08a;
}

.slider-trigger-text {
  flex: 1;
  text-align: left;
}

.slider-trigger-refresh {
  color: var(--accent);
  font-size: 12px;
}

.slider-pop {
  position: absolute;
  z-index: 1000;
  top: 48px;
  left: 0;
}

.submit-item {
  margin-bottom: 0;
}

.register-form :deep(.t-button--theme-primary) {
  height: 44px;
  border-radius: 8px;
  background: var(--accent);
  border-color: var(--accent);
}

.form-row {
  margin-top: 16px;
  font-size: 13px;
}

.link-type {
  color: var(--ink-secondary);
  text-decoration: none;
}

.link-type:hover {
  color: var(--ink-text);
}

:global(#tianai-captcha-parent) {
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.32);
  background: #f7f4ee;
  border-radius: 12px;
}

:global(#tianai-captcha .slider-tip) {
  color: #1f2933;
  font-weight: 400;
}

:global(#tianai-captcha-parent #tianai-captcha-slider-move-track-mask) {
  border-color: #2c5d8f;
  background-color: #e3ecf6;
}

@media (max-width: 900px) {
  .register {
    grid-template-columns: 1fr;
  }

  .register-intro {
    padding: 32px 24px 16px 24px;
    border-right: none;
  }

  .intro-copy {
    margin: 40px 0 32px 0;
  }

  .headline {
    font-size: 30px;
  }

  .register-panel {
    padding: 16px 24px 48px 24px;
  }
}
</style>
