import DOMPurify, { type Config } from 'dompurify';

const allowedTags = [
  'p',
  'br',
  'hr',
  'strong',
  'b',
  'em',
  'i',
  'u',
  's',
  'blockquote',
  'pre',
  'code',
  'ol',
  'ul',
  'li',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'a',
  'img',
  'span',
  'div',
  'sub',
  'sup'
];
const allowedAttributes = ['href', 'target', 'rel', 'src', 'alt', 'title', 'width', 'height', 'class', 'style'];
const colorNames = new Set([
  'black',
  'white',
  'red',
  'green',
  'blue',
  'yellow',
  'orange',
  'purple',
  'gray',
  'grey',
  'silver',
  'navy',
  'teal',
  'maroon',
  'olive',
  'lime',
  'aqua',
  'fuchsia',
  'transparent'
]);
const safeClassPattern = /^ql-(?:align-(?:center|right|justify)|indent-[1-8]|size-(?:small|large|huge)|font-(?:serif|monospace))$/;
const colorFunctionPattern = /^(?:rgb|rgba|hsl|hsla)\([\d.%\s,/+-]{1,64}\)$/i;
const config: Config = {
  ALLOWED_TAGS: allowedTags,
  ALLOWED_ATTR: allowedAttributes,
  FORBID_TAGS: [
    'script',
    'style',
    'iframe',
    'video',
    'audio',
    'source',
    'object',
    'embed',
    'svg',
    'math',
    'form',
    'input',
    'button',
    'textarea',
    'select',
    'link',
    'meta',
    'base'
  ],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
  RETURN_TRUSTED_TYPE: false
};

/** Quill output and legacy notice HTML accept only ordinary web URLs and controlled raster data URLs. */
export const isSafeNoticeUrl = (value: string, image = false): boolean => {
  const url = value.trim();
  if (!url || url.length > 4096 || /[\u0000-\u0020\u007f\\]/.test(url)) return false;
  const lower = url.toLowerCase();
  if (image && /^data:image\/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=]+$/i.test(url)) return true;
  if (/^https?:\/\//i.test(url)) {
    try {
      const parsed = new URL(url);
      return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && Boolean(parsed.hostname);
    } catch {
      return false;
    }
  }
  if (!image && /^(?:mailto:|tel:)/i.test(url)) return true;
  if (lower.startsWith('//') || url.includes(':')) return false;
  return true;
};

const safeStyle = (value: string): string => {
  const declarations: string[] = [];
  for (const declaration of value.split(';')) {
    const separator = declaration.indexOf(':');
    if (separator < 1) continue;
    const property = declaration.slice(0, separator).trim().toLowerCase();
    const candidate = declaration.slice(separator + 1).trim();
    const lower = candidate.toLowerCase();
    const accepted = (() => {
      if (property === 'color' || property === 'background-color') {
        return /^#[\da-f]{3,8}$/i.test(candidate) || colorFunctionPattern.test(candidate) || colorNames.has(lower);
      }
      if (property === 'font-size') return ['10px', '14px', '18px', '32px', 'small', 'large', 'huge'].includes(lower);
      if (property === 'font-family') return ['serif', 'sans-serif', 'monospace'].includes(lower);
      if (property === 'text-align') return ['left', 'right', 'center', 'justify'].includes(lower);
      return false;
    })();
    if (accepted) declarations.push(`${property}: ${candidate}`);
  }
  return declarations.join('; ');
};

DOMPurify.addHook('afterSanitizeAttributes', (element) => {
  for (const attribute of ['href', 'src']) {
    const value = element.getAttribute(attribute);
    if (value !== null && !isSafeNoticeUrl(value, attribute === 'src')) element.removeAttribute(attribute);
  }

  const classValue = element.getAttribute('class');
  if (classValue !== null) {
    const classes = classValue.split(/\s+/).filter((name) => safeClassPattern.test(name));
    if (classes.length) element.setAttribute('class', classes.join(' '));
    else element.removeAttribute('class');
  }

  const styleValue = element.getAttribute('style');
  if (styleValue !== null) {
    const style = safeStyle(styleValue);
    if (style) element.setAttribute('style', style);
    else element.removeAttribute('style');
  }

  if (element.tagName === 'A' && element.getAttribute('target') === '_blank' && element.hasAttribute('href')) {
    element.setAttribute('rel', 'noopener noreferrer');
  } else if (element.tagName === 'A' && element.getAttribute('target') !== '_blank') {
    element.removeAttribute('target');
  }

  if (element.tagName === 'IMG') {
    for (const dimension of ['width', 'height']) {
      const value = element.getAttribute(dimension);
      if (value !== null && (!/^[1-9]\d{0,3}$/.test(value) || Number(value) > 2000)) element.removeAttribute(dimension);
    }
    const alt = element.getAttribute('alt');
    if (alt !== null && alt.length > 300) element.setAttribute('alt', alt.slice(0, 300));
  }
});

/**
 * Sanitize both pre-existing editor input and every HTML string emitted for persistence/display.
 */
export const sanitizeNoticeHtml = (html: unknown): string => {
  if (typeof html !== 'string' || !html) return '';
  return DOMPurify.sanitize(html, config) as string;
};
