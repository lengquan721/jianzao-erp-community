<template>
  <div class="login">
    <!-- 左侧：品牌 + 登录表单，整屏唯一可交互区域 -->
    <section class="login-panel">
      <header class="brand">
        <img class="brand-mark" :src="logo" alt="" />
        <span class="brand-name">{{ title }}</span>
        <span class="brand-tag">{{ proxy.$t('login.brandTag') }}</span>
      </header>

      <div class="login-body">
        <div class="headline">
          <h1>
            {{ proxy.$t('login.headlinePrefix') }}
            <span class="headline-brand">{{ title }}</span>
          </h1>
          <div class="headline-slogan">{{ proxy.$t('login.slogan') }}</div>
          <p class="sub">
            <template v-if="register">
              {{ proxy.$t('login.noAccount') }}
              <router-link class="sub-link" :to="'/register'">{{ proxy.$t('login.switchRegisterPage') }}</router-link>
            </template>
            <template v-else>{{ proxy.$t('login.contactAdmin') }}</template>
          </p>
        </div>

        <t-form ref="loginRef" :data="loginForm" :rules="loginRules" class="login-form" label-align="top">
          <t-form-item
            v-if="tenantEnabled && !tenantAutoMatched"
            name="tenantId"
            :label="proxy.$t('login.company')"
            :required-mark="false"
            class="form-item"
          >
            <t-select
              v-model="loginForm.tenantId"
              filterable
              :placeholder="proxy.$t('login.selectPlaceholder')"
              :options="tenantOptions"
              size="large"
            >
              <template #prefix-icon><svg-icon icon-class="company" class="input-icon" /></template>
            </t-select>
          </t-form-item>
          <div v-else-if="tenantEnabled && tenantAutoMatched" class="tenant-auto-tip">
            <svg-icon icon-class="company" class="input-icon" />
            <span>{{ matchedTenantName }}</span>
          </div>

          <t-form-item name="username" :label="proxy.$t('login.username')" :required-mark="false" class="form-item">
            <t-input v-model="loginForm.username" size="large" autocomplete="off" :placeholder="proxy.$t('login.usernamePlaceholder')">
              <template #prefix-icon><svg-icon icon-class="user" class="input-icon" /></template>
            </t-input>
          </t-form-item>

          <t-form-item name="password" :label="proxy.$t('login.password')" :required-mark="false" class="form-item">
            <t-input
              v-model="loginForm.password"
              :type="showPassword ? 'text' : 'password'"
              size="large"
              autocomplete="off"
              :placeholder="proxy.$t('login.passwordPlaceholder')"
              @enter="handleLogin"
            >
              <template #prefix-icon><svg-icon icon-class="password" class="input-icon" /></template>
              <template #suffix-icon>
                <span class="pwd-toggle" @click.stop="showPassword = !showPassword">
                  <svg-icon :icon-class="showPassword ? 'eye-open' : 'eye'" class="input-icon" />
                </span>
              </template>
            </t-input>
          </t-form-item>

          <!-- 滑块验证：服务端生成拼图并校验轨迹，通过后才拿到登录票据 -->
          <t-form-item v-if="captchaEnabled" name="code" :required-mark="false" class="form-item captcha-item">
            <button type="button" class="slider-trigger" :class="{ 'is-verified': sliderVerified }" @click="openSlider">
              <span class="slider-trigger-icon">
                <!-- 未验证用盾牌、已验证用对勾，与弹窗标题图标同一套线条 -->
                <svg v-if="!sliderVerified" viewBox="0 0 20 20" aria-hidden="true">
                  <path
                    d="M10 2.2 4 4.6v4.6c0 3.4 2.4 6.3 6 7.4 3.6-1.1 6-4 6-7.4V4.6L10 2.2Z"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.4"
                    stroke-linejoin="round"
                  />
                </svg>
                <svg-icon v-else icon-class="checkbox" />
              </span>
              <span class="slider-trigger-text">{{ sliderVerified ? proxy.$t('slider.verified') : proxy.$t('slider.trigger') }}</span>
              <span class="slider-trigger-action">{{ sliderVerified ? proxy.$t('slider.refresh') : proxy.$t('slider.open') }}</span>
            </button>
            <!-- 滑块验证卡片：内容由 tac SDK 渲染，外壳与文案由该组件负责，位置跟随本表单项 -->
            <slider-captcha ref="sliderRef" @success="onSliderSuccess" @close="onSliderClose" />
          </t-form-item>

          <div class="form-row">
            <t-checkbox v-model="loginForm.rememberMe">{{ proxy.$t('login.rememberPassword') }}</t-checkbox>
          </div>

          <t-form-item class="form-item submit-item">
            <t-button :loading="loading" size="large" theme="primary" block @click.prevent="handleLogin">
              <span v-if="!loading">{{ proxy.$t('login.login') }}</span>
              <span v-else>{{ proxy.$t('login.logging') }}</span>
              <svg v-if="!loading" class="submit-arrow" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M2 8h11M9 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </t-button>
          </t-form-item>
        </t-form>
      </div>

      <!-- 商业推广未经部署方明确授权，不渲染对外入口；保留上游翻译但不据此推定授权。 -->

      <footer class="login-foot">
        <span class="copyright">{{ proxy.$t('login.copyright') }}</span>
        <lang-select class="lang" />
      </footer>
    </section>

    <!-- 右侧：品牌文案 + 等距 ERP 窗口示意 -->
    <section class="login-hero">
      <svg class="hero-grid" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="login-blueprint" width="40" height="40" patternUnits="userSpaceOnUse">
            <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#C3C6D7" stroke-width="0.8" stroke-dasharray="2 3" />
            <circle cx="40" cy="40" r="1.5" fill="#C3C6D7" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#login-blueprint)" />
      </svg>
      <span class="hero-glow" aria-hidden="true"></span>

      <div class="hero-copy">
        <h2>{{ proxy.$t('login.headline') }}</h2>
        <p>{{ proxy.$t('login.subhead') }}</p>
      </div>

      <div class="hero-stage" aria-hidden="true">
        <div class="hero-window-wrap">
          <div class="hero-window">
            <div class="win-bar">
              <span class="win-dot win-dot-r"></span>
              <span class="win-dot win-dot-y"></span>
              <span class="win-dot win-dot-g"></span>
              <span class="win-title"></span>
              <span class="win-avatar"></span>
            </div>
            <div class="win-body">
              <div class="win-side">
                <span class="side-row side-row-active"><i></i><b></b></span>
                <span class="side-row"></span>
                <span class="side-row side-row-short"></span>
                <span class="side-row"></span>
                <span class="side-foot"><i></i><b></b></span>
              </div>
              <div class="win-main">
                <div class="kpi-row">
                  <span class="kpi kpi-a"></span>
                  <span class="kpi kpi-b"></span>
                  <span class="kpi kpi-c"></span>
                </div>
                <div class="chart">
                  <svg viewBox="0 0 200 45" preserveAspectRatio="none">
                    <path d="M0,34 L28,22 L56,27 L88,14 L118,19 L150,9 L200,11 L200,45 L0,45 Z" fill="rgba(0, 82, 217, 0.08)" />
                    <path
                      d="M0,34 L28,22 L56,27 L88,14 L118,19 L150,9 L200,11"
                      fill="none"
                      stroke="#0052D9"
                      stroke-width="2.5"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                    <circle cx="150" cy="9" r="3.5" fill="#0052D9" />
                    <circle cx="200" cy="11" r="3.5" fill="#00A870" />
                  </svg>
                </div>
                <div class="row-row"><span></span><b></b></div>
                <div class="row-row row-row-short"><span></span><b></b></div>
              </div>
            </div>
          </div>
          <div class="hero-badge"><img :src="logo" alt="" /></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { getCodeImg, getTenantList } from '@/api/login';
import logo from '@/assets/logo/logo.svg';
import { useUserStore } from '@/store/modules/user';
import { LoginData, TenantVO } from '@/api/types';
import SliderCaptcha from '@/components/SliderCaptcha/index.vue';
import { to } from 'await-to-js';
import { useI18n } from 'vue-i18n';
import { MessagePlugin } from 'tdesign-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const title = import.meta.env.VITE_APP_TITLE;
const userStore = useUserStore();
const router = useRouter();
const { t } = useI18n();

const loginForm = ref<LoginData>({
  tenantId: '000000',
  username: '',
  password: '',
  rememberMe: false,
  code: '',
  uuid: ''
} as LoginData);

// 密码明文切换
const showPassword = ref(false);
// 滑块是否通过服务端校验
const sliderVerified = ref(false);

/**
 * t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，用宽松类型声明。
 * code（滑块票据）只在滑块校验通过后才加入规则：规则对象变化会触发 form-item 的
 * change 校验，若初始就挂上必填规则，页面加载会直接报「请完成安全验证」。
 */
const loginRules = computed<Record<string, any>>(() => {
  const rules: Record<string, any> = {
    tenantId: [{ required: true, trigger: 'blur', message: t('login.rule.tenantId.required') }],
    username: [{ required: true, trigger: 'blur', message: t('login.rule.username.required') }],
    password: [{ required: true, trigger: 'blur', message: t('login.rule.password.required') }]
  };
  if (sliderVerified.value) {
    rules.code = [{ required: true, trigger: 'change', message: t('login.rule.code.required') }];
  }
  return rules;
});

const loading = ref(false);
// 验证码开关（默认 false，等 /auth/tenant/list 返回后再按后端配置打开）
const captchaEnabled = ref(false);
// 租户开关
const tenantEnabled = ref(true);
// 域名/列表唯一命中时隐藏公司选择框
const tenantAutoMatched = ref(false);

// 注册开关
const register = ref(false);
const redirect = ref('/');
const loginRef = ref<any>();
const sliderRef = ref<InstanceType<typeof SliderCaptcha>>();
// 租户列表
const tenantList = ref<TenantVO[]>([]);

/** t-select 用 :options 的 {label,value} 数组，租户下拉由后端列表映射（DOC/35 §3） */
const tenantOptions = computed(() => tenantList.value.map((item) => ({ label: item.companyName, value: item.tenantId })));

watch(
  () => router.currentRoute.value,
  (newRoute: any) => {
    redirect.value = newRoute.query && newRoute.query.redirect && decodeURIComponent(newRoute.query.redirect);
  },
  { immediate: true }
);

/** 打开滑块：每次登录或刷新都必须重新走服务端校验，旧票据立即作废 */
const openSlider = () => {
  loginForm.value.code = '';
  loginForm.value.uuid = '';
  sliderVerified.value = false;
  sliderRef.value?.open();
};

/** 滑块校验通过：把服务端签发的一次性票据回填到登录表单 */
const onSliderSuccess = ({ uuid, code }: { uuid: string; code: string }) => {
  loginForm.value.uuid = uuid;
  loginForm.value.code = code;
  sliderVerified.value = true;
};

/** 用户关闭滑块：保持未验证状态 */
const onSliderClose = () => {
  sliderVerified.value = false;
};

/** 登录：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const handleLogin = async () => {
  const result = await loginRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  // 滑块未通过时先引导完成验证，不发起登录请求
  if (captchaEnabled.value && !sliderVerified.value) {
    MessagePlugin.warning(t('slider.pending'));
    openSlider();
    return;
  }
  loading.value = true;
  // 勾选记住用户名时仅保存租户与用户名，禁止在 localStorage 落明文密码
  if (loginForm.value.rememberMe) {
    localStorage.setItem('tenantId', String(loginForm.value.tenantId));
    localStorage.setItem('username', String(loginForm.value.username));
    localStorage.setItem('rememberMe', String(loginForm.value.rememberMe));
  } else {
    localStorage.removeItem('tenantId');
    localStorage.removeItem('username');
    localStorage.removeItem('rememberMe');
  }
  const [err] = await to(userStore.login(loginForm.value));
  if (!err) {
    await router.push(redirect.value || '/');
    loading.value = false;
  } else {
    loading.value = false;
    // 登录失败后票据已被服务端消费，必须重新滑动
    openSlider();
  }
};

/** 读取验证码开关：滑块验证器由 /auth/code/slider 自行生成拼图，此处只判断开关 */
const getCode = async () => {
  const { data } = await getCodeImg();
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
};

const getLoginData = () => {
  const tenantId = localStorage.getItem('tenantId');
  const username = localStorage.getItem('username');
  const rememberMe = localStorage.getItem('rememberMe');
  // 清理旧版本遗留的明文密码
  localStorage.removeItem('password');
  loginForm.value = {
    tenantId: tenantId === null ? String(loginForm.value.tenantId) : tenantId,
    username: username === null ? String(loginForm.value.username) : username,
    rememberMe: rememberMe === 'true'
  } as LoginData;
};

/** 获取租户列表（后端按访问域名过滤；命中唯一或仅一家时自动选中并隐藏选择框） */
const initTenantList = async () => {
  const { data } = await getTenantList(false);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    tenantList.value = data.voList;
    if (tenantList.value != null && tenantList.value.length === 1) {
      loginForm.value.tenantId = tenantList.value[0].tenantId;
      tenantAutoMatched.value = true;
    } else if (tenantList.value != null && tenantList.value.length !== 0) {
      loginForm.value.tenantId = tenantList.value[0].tenantId;
    }
  }
};

const matchedTenantName = computed(() => {
  const matched = tenantList.value?.find((item) => item.tenantId === loginForm.value.tenantId);
  return matched ? matched.companyName : '';
});

onMounted(() => {
  getCode();
  initTenantList();
  getLoginData();
});
</script>

<style lang="scss" scoped>
/* 品牌方向：浅底 + TDesign 蓝（#0052D9）单一强调色；右侧等距窗口是唯一的「内容密度」 */
.login {
  --brand: #0052d9;
  --brand-strong: #003ea8;
  --ink: #181c23;
  --ink-2: #434654;
  --ink-3: #7b7f8c;
  --line: #e7e8ec;
  --surface: #ffffff;
  --surface-2: #f7f8fa;
  --hero-from: #f9f9ff;
  --hero-to: #ebedf9;
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  min-height: 100%;
  background: var(--surface);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ---------- 左侧表单 ---------- */
.login-panel {
  display: flex;
  flex-direction: column;
  padding: 40px 56px 28px 56px;
  background: var(--surface);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 28px;
  height: 28px;
  display: block;
  border-radius: 7px;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.brand-tag {
  margin-left: 2px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--surface-2);
  color: var(--ink-3);
  font-size: 12px;
  line-height: 18px;
}

.login-body {
  width: min(400px, 100%);
  margin: auto;
  padding: 40px 0;
}

.headline h1 {
  margin: 0;
  font-size: 36px;
  line-height: 1.22;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.headline-brand {
  display: block;
  color: var(--brand);
}

.headline-slogan {
  margin-top: 10px;
  display: inline-block;
  padding: 4px 10px;
  background: rgba(0, 82, 217, 0.08);
  border: 1px solid rgba(0, 82, 217, 0.16);
  border-radius: 6px;
  color: var(--brand);
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
}

.headline .sub {
  margin: 14px 0 0 0;
  color: var(--ink-3);
  font-size: 14px;
  line-height: 22px;
}

.sub-link {
  color: var(--brand);
  font-weight: 500;
  text-decoration: none;
}

.sub-link:hover {
  text-decoration: underline;
}

.login-form {
  margin-top: 34px;
  color: var(--ink);
  --td-error-color: #d54941;
}

.form-item {
  margin-bottom: 18px;
}

.login-form :deep(.captcha-item) {
  width: 100%;
}

.login-form :deep(.captcha-item .t-form__controls) {
  width: 100%;
  margin-left: 0 !important;
  margin-right: 0 !important;
}

.login-form :deep(.captcha-item .t-form__controls-content) {
  width: 100%;
  display: block;
}

.login-form :deep(.t-form__label) {
  padding-bottom: 6px;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
}

.login-form :deep(.t-input) {
  height: 46px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--surface);
  box-shadow: inset 0 0 0 1px var(--line);
}

.login-form :deep(.t-select) {
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}

.login-form :deep(.t-input--focused),
.login-form :deep(.t-input.t-is-focused),
.login-form :deep(.t-select.t-is-active .t-input) {
  box-shadow:
    inset 0 0 0 1px var(--brand),
    0 0 0 3px rgba(0, 82, 217, 0.12);
}

.login-form :deep(.t-input__inner) {
  color: var(--ink);
  font-size: 14px;
}

.input-icon {
  width: 17px;
  height: 17px;
  color: var(--ink-3);
}

.pwd-toggle {
  display: inline-flex;
  cursor: pointer;
}

.tenant-auto-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 46px;
  padding: 0 12px;
  margin-bottom: 18px;
  border-radius: 8px;
  box-shadow: inset 0 0 0 1px var(--line);
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: 14px;
}

/* 验证条：安静的一行，通过后才出现绿色描边 */
.slider-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
  margin: 0;
  height: 46px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: var(--surface-2);
  box-shadow: inset 0 0 0 1px var(--line);
  color: var(--ink-2);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition:
    box-shadow 150ms ease,
    color 150ms ease;
}

.slider-trigger:hover {
  box-shadow: inset 0 0 0 1px var(--brand);
}

.slider-trigger.is-verified {
  box-shadow: inset 0 0 0 1px rgba(0, 168, 112, 0.55);
  color: var(--ink);
}

.slider-trigger-icon {
  display: inline-flex;
  width: 17px;
  height: 17px;
  color: var(--ink-3);
}

.slider-trigger-icon svg {
  width: 100%;
  height: 100%;
}

.slider-trigger.is-verified .slider-trigger-icon {
  color: #00a870;
}

.slider-trigger-text {
  flex: 1;
  text-align: left;
}

.slider-trigger-action {
  color: var(--brand);
  font-size: 12px;
}

.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 22px 0;
  font-size: 13px;
}

.login-form :deep(.t-checkbox__label) {
  color: var(--ink-2);
  font-size: 13px;
}

.submit-item {
  margin-bottom: 0;
}

.login-form :deep(.t-button--theme-primary) {
  height: 48px;
  border-radius: 8px;
  background: var(--brand);
  border-color: var(--brand);
  box-shadow: 0 6px 18px rgba(0, 82, 217, 0.24);
  font-size: 15px;
  font-weight: 600;
}

.login-form :deep(.t-button--theme-primary:hover) {
  background: var(--brand-strong);
  border-color: var(--brand-strong);
  box-shadow: 0 8px 22px rgba(0, 82, 217, 0.3);
}

.submit-arrow {
  width: 16px;
  height: 16px;
  margin-left: 6px;
}

.login-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--ink-3);
  font-size: 12px;
}

.login-commercial {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12px;
}

.login-commercial a {
  color: var(--td-brand-color);
  text-decoration: none;
}

.login-commercial a:hover {
  text-decoration: underline;
}

.login-foot .lang :deep(.lang-select--style) {
  color: var(--ink-3);
  font-size: 16px;
}

/* ---------- 右侧：品牌文案 + 等距窗口 ---------- */
.login-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 64px 56px 48px 56px;
  overflow: hidden;
  background: linear-gradient(135deg, var(--hero-from), var(--hero-to));
}

.hero-grid {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.42;
}

.hero-glow {
  position: absolute;
  top: 10%;
  left: 4%;
  width: 520px;
  height: 520px;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, rgba(0, 82, 217, 0.16), rgba(0, 82, 217, 0));
  filter: blur(30px);
}

.hero-copy {
  position: relative;
  z-index: 2;
  max-width: 460px;
}

.hero-copy h2 {
  margin: 0;
  font-size: 28px;
  line-height: 1.4;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.hero-copy p {
  margin: 12px 0 0 0;
  max-width: 36ch;
  color: var(--ink-3);
  font-size: 14px;
  line-height: 1.85;
}

.hero-stage {
  position: relative;
  z-index: 2;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  perspective: 1100px;
}

/* 窗口与角标共用同一个定位盒，角标才咬得住窗口左上角 */
.hero-window-wrap {
  position: relative;
}

/* 等距窗口：斜置的 ERP 界面骨架，只表达「一块看板」，不承载真实数据 */
.hero-window {
  position: relative;
  width: 460px;
  height: 300px;
  padding: 20px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.95);
  transform: rotateY(-14deg) rotateX(11deg) rotateZ(3deg);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.8),
    0 30px 60px -15px rgba(0, 30, 80, 0.14),
    0 10px 20px -5px rgba(0, 30, 80, 0.06);
}

.win-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 14px;
  margin-bottom: 16px;
}

.win-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.win-dot-r {
  background: rgba(255, 95, 87, 0.8);
}

.win-dot-y {
  background: rgba(254, 188, 46, 0.8);
}

.win-dot-g {
  background: rgba(40, 200, 64, 0.8);
}

.win-title {
  width: 112px;
  height: 14px;
  margin-left: 12px;
  border-radius: 999px;
  background: #ebedf9;
}

.win-avatar {
  width: 22px;
  height: 22px;
  margin-left: auto;
  border-radius: 50%;
  background: #dfe2ed;
}

.win-body {
  display: grid;
  grid-template-columns: 3fr 9fr;
  gap: 12px;
  height: 200px;
}

.win-side {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 10px;
  border-radius: 12px;
  background: rgba(241, 243, 255, 0.7);
}

.side-row {
  display: block;
  height: 14px;
  border-radius: 4px;
  background: rgba(223, 226, 237, 0.75);
}

.side-row-short {
  width: 68%;
}

.side-row-active {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 20px;
  padding: 0 6px;
  background: rgba(0, 82, 217, 0.14);
}

.side-row-active i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
}

.side-row-active b {
  width: 40px;
  height: 6px;
  border-radius: 3px;
  background: rgba(0, 82, 217, 0.45);
}

.side-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  background: rgba(120, 251, 187, 0.35);
}

.side-foot i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #006c47;
}

.side-foot b {
  width: 32px;
  height: 5px;
  border-radius: 3px;
  background: rgba(0, 108, 71, 0.4);
}

.win-main {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.kpi {
  display: block;
  height: 44px;
  border-radius: 8px;
  background: rgba(241, 243, 255, 0.75);
}

.kpi-b {
  background: rgba(120, 251, 187, 0.22);
}

.kpi-c {
  background: rgba(0, 82, 217, 0.1);
}

.chart {
  height: 58px;
  padding: 6px 8px;
  border-radius: 8px;
  background: rgba(241, 243, 255, 0.6);
}

.chart svg {
  width: 100%;
  height: 100%;
}

.row-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 12px;
  padding: 0 8px;
  border-radius: 4px;
  background: rgba(229, 232, 243, 0.7);
}

.row-row span {
  width: 56px;
  height: 5px;
  border-radius: 3px;
  background: rgba(115, 118, 134, 0.35);
}

.row-row b {
  width: 30px;
  height: 5px;
  border-radius: 3px;
  background: #00a870;
}

.row-row-short span {
  width: 64px;
}

.row-row-short b {
  width: 24px;
  background: rgba(0, 82, 217, 0.7);
}

.hero-badge {
  position: absolute;
  top: -30px;
  left: -26px;
  width: 76px;
  height: 76px;
  padding: 13px;
  border-radius: 19px;
  background: linear-gradient(135deg, #2f6bff, #003da6);
  box-shadow: 0 16px 36px rgba(0, 82, 217, 0.35);
  transform: rotate(-6deg);
  transition: transform 300ms ease;
}

.hero-window-wrap:hover .hero-badge {
  transform: rotate(0deg);
}

.hero-badge img {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: 6px;
}

@media (max-width: 1080px) {
  .login {
    grid-template-columns: 1fr;
  }

  .login-panel {
    padding: 32px 24px 20px 24px;
  }

  .login-hero {
    display: none;
  }

  .login-body {
    width: min(420px, 100%);
    padding: 32px 0;
  }

  .headline h1 {
    font-size: 30px;
  }
}
</style>

/* ---------- 暗色档：登录页独占 .login 命名，用非 scoped 块挂到 html.dark ---------- */
<style lang="scss">
html.dark .login {
  --ink: #edf0f7;
  --ink-2: #b4b9c7;
  --ink-3: #8b93a5;
  --line: #2b3140;
  --surface: #12161f;
  --surface-2: #1b2130;
  --brand: #3d7bf5;
  --brand-strong: #5b93ff;
  --hero-from: #12161f;
  --hero-to: #1a2233;
}

html.dark .hero-window {
  background: rgba(24, 30, 43, 0.95);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.05),
    0 30px 60px -20px rgba(0, 0, 0, 0.6);
}

html.dark .win-title,
html.dark .win-avatar {
  background: #232a3a;
}

html.dark .win-side,
html.dark .kpi,
html.dark .chart {
  background: rgba(35, 42, 58, 0.7);
}

html.dark .side-row,
html.dark .row-row {
  background: rgba(43, 49, 64, 0.9);
}
</style>
