import axios, { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { useUserStore } from '@/store/modules/user';
import { getToken } from '@/utils/auth';
import { tansParams, blobValidate } from '@/utils/ruoyi';
import cache from '@/plugins/cache';
import { HttpStatus } from '@/enums/RespEnum';
import { errorCode } from '@/utils/errorCode';
import { DialogPlugin, MessagePlugin, NotifyPlugin, LoadingPlugin } from 'tdesign-vue-next';
import type { DialogInstance, LoadingInstance } from 'tdesign-vue-next';
import FileSaver from 'file-saver';
import { getLanguage } from '@/lang';
import { encryptBase64, encryptWithAes, generateAesKey, decryptWithAes, decryptBase64 } from '@/utils/crypto';
import { encrypt, decrypt } from '@/utils/jsencrypt';
import router from '@/router';

const encryptHeader = 'encrypt-key';
let downloadLoadingInstance: LoadingInstance;
// 是否显示重新登录
export const isRelogin = { show: false };
export const globalHeaders = () => {
  return {
    Authorization: 'Bearer ' + getToken(),
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  };
};

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8';
axios.defaults.headers['clientid'] = import.meta.env.VITE_APP_CLIENT_ID;

/**
 * 防重复提交缓存的体积上限（字符数）。
 * sessionStorage 总配额约 5MB，留一半余量给其他数据。
 */
const SUBMIT_CACHE_LIMIT = 512 * 1024;

/** 写防重缓存：超配额时静默降级，不能因为缓存失败拦掉正常请求 */
function safeSetSubmitCache(requestObj: { url?: string; data?: any; time: number }) {
  try {
    cache.session.setJSON('sessionObj', requestObj);
  } catch {
    /* 配额不足：放弃防重记录，请求照常发出 */
  }
}
// 创建 axios 实例
const service = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 50000,
  transitional: {
    // 超时错误更明确
    clarifyTimeoutError: true
  }
});

// 实例在创建时快照全局 defaults，此处显式补齐 clientid（Sa-Token 客户端校验必需）
service.defaults.headers['clientid'] = import.meta.env.VITE_APP_CLIENT_ID;

// 请求拦截器
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 对应国际化资源文件后缀
    config.headers['Content-Language'] = getLanguage();

    const isToken = config.headers?.isToken === false;
    // 是否需要防止数据重复提交
    const isRepeatSubmit = config.headers?.repeatSubmit === false;
    // 是否需要加密
    const isEncrypt = config.headers?.isEncrypt === 'true';

    if (getToken() && !isToken) {
      config.headers['Authorization'] = 'Bearer ' + getToken(); // 让每个请求携带自定义token 请根据实际情况自行修改
    }
    // get请求映射params参数
    if (config.method === 'get' && config.params) {
      let url = config.url + '?' + tansParams(config.params);
      url = url.slice(0, -1);
      config.params = {};
      config.url = url;
    }

    if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
      const rawData = typeof config.data === 'object' ? JSON.stringify(config.data) : config.data;
      const requestObj = {
        url: config.url,
        data: rawData,
        time: new Date().getTime()
      };
      // sessionStorage 配额约 5MB，超大请求体（如内嵌 base64 图片的组件树）
      // 写进去会抛 QuotaExceededError，整个请求被拦掉且没有任何提示。
      // 这类请求体积大、不易在 500ms 内重复提交，直接跳过防重即可。
      const tooLargeToCache = typeof rawData === 'string' && rawData.length > SUBMIT_CACHE_LIMIT;
      if (!tooLargeToCache) {
        const sessionObj = cache.session.getJSON('sessionObj');
        if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
          safeSetSubmitCache(requestObj);
        } else {
          const s_url = sessionObj.url; // 请求地址
          const s_data = sessionObj.data; // 请求数据
          const s_time = sessionObj.time; // 请求时间
          const interval = 500; // 间隔时间(ms)，小于此时间视为重复提交
          if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
            const message = '数据正在处理，请勿重复提交';
            console.warn(`[${s_url}]: ` + message);
            return Promise.reject(new Error(message));
          } else {
            safeSetSubmitCache(requestObj);
          }
        }
      }
    }
    if (import.meta.env.VITE_APP_ENCRYPT === 'true') {
      // 当开启参数加密
      if (isEncrypt && (config.method === 'post' || config.method === 'put')) {
        // 生成一个 AES 密钥
        const aesKey = generateAesKey();
        config.headers[encryptHeader] = encrypt(encryptBase64(aesKey));
        config.data = typeof config.data === 'object' ? encryptWithAes(JSON.stringify(config.data), aesKey) : encryptWithAes(config.data, aesKey);
      }
    }
    // FormData数据去请求头Content-Type
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type'];
    }
    return config;
  },
  (error: any) => {
    return Promise.reject(error);
  }
);

// 响应拦截器
service.interceptors.response.use(
  (res: AxiosResponse) => {
    if (import.meta.env.VITE_APP_ENCRYPT === 'true') {
      // 加密后的 AES 秘钥
      const keyStr = res.headers[encryptHeader];
      // 加密
      if (keyStr != null && keyStr != '') {
        const data = res.data;
        // 请求体 AES 解密
        const base64Str = decrypt(keyStr);
        // base64 解码 得到请求头的 AES 秘钥
        const aesKey = decryptBase64(base64Str.toString());
        // aesKey 解码 data
        const decryptData = decryptWithAes(data, aesKey);
        // 将结果 (得到的是 JSON 字符串) 转为 JSON
        res.data = JSON.parse(decryptData);
      }
    }
    // 未设置状态码则默认成功状态
    const code = res.data.code || HttpStatus.SUCCESS;
    // 获取错误信息
    const msg = errorCode[code] || res.data.msg || errorCode['default'];
    // 登录类请求可声明 silentError：业务失败文案由登录页统一呈现，避免请求层先弹后端差异化提示
    // axios 会把非 false 的自定义头值统一转成字符串，这里按 'true' 比较
    const silentError = res.config.headers?.silentError === 'true';
    // 二进制/纯文本数据则直接返回
    if (res.request.responseType === 'blob' || res.request.responseType === 'arraybuffer' || res.request.responseType === 'text') {
      return res.data;
    }
    if (code === 401) {
      // prettier-ignore
      if (!isRelogin.show) {
        isRelogin.show = true;
        // TDesign DialogPlugin 返回实例而非 Promise，包装成真 Promise：确定 resolve、取消/关闭/ESC/遮罩均 reject，保持原 .then().catch() 链语义
        new Promise<void>((resolve, reject) => {
          let dismissed = false;
          // TDesign 取消会连带触发 onCancel 与 onClose，用标记保证只销毁一次；resolve/reject 本身只结算一次
          const dismiss = (dialog: DialogInstance) => {
            if (dismissed) return;
            dismissed = true;
            dialog.hide();
            dialog.destroy();
          };
          const reloginDialog = DialogPlugin.confirm({
            attach: 'body',
            header: '系统提示',
            body: '登录状态已过期，您可以继续留在该页面，或者重新登录',
            confirmBtn: '重新登录',
            cancelBtn: '取消',
            theme: 'warning',
            closeOnEscKeydown: true,
            closeOnOverlayClick: true,
            onConfirm: () => {
              dismiss(reloginDialog);
              resolve();
            },
            onCancel: () => {
              dismiss(reloginDialog);
              reject('cancel');
            },
            // esc / 关闭按钮 / 点遮罩均触发 onClose
            onClose: () => {
              dismiss(reloginDialog);
              reject('close');
            }
          });
        }).then(() => {
          isRelogin.show = false;
          useUserStore().logout().then(() => {
            router.replace({
              path: '/login',
              query: {
                redirect: encodeURIComponent(router.currentRoute.value.fullPath || '/')
              }
            })
          });
        }).catch(() => {
          isRelogin.show = false;
        });
      }
      return Promise.reject('无效的会话，或者会话已过期，请重新登录。');
    } else if (code === HttpStatus.SERVER_ERROR) {
      if (!silentError) MessagePlugin.error(msg);
      return Promise.reject(new Error(msg));
    } else if (code === HttpStatus.WARN) {
      if (!silentError) MessagePlugin.warning(msg);
      return Promise.reject(new Error(msg));
    } else if (code !== HttpStatus.SUCCESS) {
      if (!silentError) NotifyPlugin.error({ title: msg });
      return Promise.reject('error');
    } else {
      return Promise.resolve(res.data);
    }
  },
  (error: any) => {
    let { message } = error;
    if (message == 'Network Error') {
      message = '后端接口连接异常';
    } else if (message.includes('timeout')) {
      message = '系统接口请求超时';
    } else if (message.includes('Request failed with status code')) {
      message = '系统接口' + message.substr(message.length - 3) + '异常';
    }
    // 探测型请求（如尚未落地的 /system/tenant/current）可声明 silentHttpError，接口不存在时由调用方降级，不打扰用户
    const silentHttpError = error.config?.headers?.silentHttpError === 'true';
    if (!silentHttpError) MessagePlugin.error(message, 5 * 1000);
    return Promise.reject(error);
  }
);
// 通用下载方法
export function download(url: string, params: any, fileName: string) {
  downloadLoadingInstance = LoadingPlugin({ fullscreen: true, text: '正在下载数据，请稍候', preventScrollThrough: true });
  // prettier-ignore
  return service.post(url, params, {
      transformRequest: [
        (params: any) => {
          return tansParams(params);
        }
      ],
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      responseType: 'blob'
    }).then(async (resp: any) => {
      const isLogin = blobValidate(resp);
      if (isLogin) {
        const blob = new Blob([resp]);
        FileSaver.saveAs(blob, fileName);
      } else {
        const blob = new Blob([resp]);
        const resText = await blob.text();
        const rspObj = JSON.parse(resText);
        const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default'];
        MessagePlugin.error(errMsg);
      }
      downloadLoadingInstance.hide();
    }).catch((r: any) => {
      console.error(r);
      MessagePlugin.error('下载文件出现错误，请联系管理员！');
      downloadLoadingInstance.hide();
    });
}
// 导出 axios 实例
export default service;
