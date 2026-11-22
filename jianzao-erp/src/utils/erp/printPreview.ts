/** 打印接口使用 text 透传：错误 JSON、对象或普通文本不能成为可打印内容。
 * 此处只校验响应形状，不代替服务端 HTML 净化；iframe 仍禁止脚本。
 */
export function isPrintableHtml(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const text = value.trim();
  return !!text && !/^[\[{]/.test(text) && /<(?:!doctype\s+html\b|[a-z][\w:-]*(?:\s[^<>]*|\s*\/)?)>/i.test(text);
}
