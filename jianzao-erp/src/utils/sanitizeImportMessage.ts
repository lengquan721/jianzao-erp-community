import DOMPurify from 'dompurify';

/** 导入结果包含不可信的账号/异常信息，只允许文本和无属性的换行标签。 */
export const sanitizeImportMessage = (message: unknown): string => {
  if (typeof message !== 'string' || !message) return '';
  return DOMPurify.sanitize(message, {
    ALLOWED_TAGS: ['br'],
    ALLOWED_ATTR: [],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    RETURN_TRUSTED_TYPE: false
  });
};
