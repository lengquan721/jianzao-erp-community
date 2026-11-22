// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { sanitizeImportMessage } from './sanitizeImportMessage';

// 仅断言本地净化后的文本/DOM结构，不将jsdom结果视为浏览器利用已复现。
const inspectMessage = (message: unknown) => {
  const clean = sanitizeImportMessage(message);
  const container = document.createElement('div');
  container.innerHTML = clean;
  for (const node of Array.from(container.childNodes)) {
    expect(node.nodeType === Node.TEXT_NODE || (node.nodeType === Node.ELEMENT_NODE && node.nodeName === 'BR')).toBe(true);
  }
  for (const element of Array.from(container.querySelectorAll('*'))) {
    expect(element.tagName).toBe('BR');
    expect(element.attributes).toHaveLength(0);
  }
  return { clean, container };
};

describe('sanitizeImportMessage', () => {
  it('preserves Chinese import details and normalizes br line breaks', () => {
    const { clean, container } = inspectMessage(
      '恭喜您，数据已全部导入成功！共 2 条，数据如下：<br/>1、账号 张三 导入成功<BR />2、账号 李四 更新成功<br>处理结束'
    );

    expect(clean).toBe('恭喜您，数据已全部导入成功！共 2 条，数据如下：<br>1、账号 张三 导入成功<br>2、账号 李四 更新成功<br>处理结束');
    expect(container.querySelectorAll('br')).toHaveLength(3);
    expect(container.textContent).toContain('1、账号 张三 导入成功');
    expect(container.textContent).toContain('2、账号 李四 更新成功');
  });

  it('keeps encoded markup as literal message text without decoding it into elements', () => {
    const { clean, container } = inspectMessage('账号 &lt;img src=x onerror=activePayload()&gt;<br/>异常：A &amp; B');

    expect(clean).toBe('账号 &lt;img src=x onerror=activePayload()&gt;<br>异常：A &amp; B');
    expect(container.textContent).toBe('账号 <img src=x onerror=activePayload()>异常：A & B');
    expect(container.querySelector('img')).toBeNull();
  });

  it('removes every attribute from the only allowed tag including data and aria attributes', () => {
    const { clean } = inspectMessage(
      '第一行<br id="result" class="message" style="color:red" title="提示" onclick="activePayload()" data-value="x" aria-label="提示">第二行'
    );

    expect(clean).toBe('第一行<br>第二行');
  });

  it('strips other formatting tags while retaining their ordinary text', () => {
    const { clean } = inspectMessage('<div>详情：<strong>张三</strong><br/><span>请联系<em>管理员</em></span></div>');

    expect(clean).toBe('详情：张三<br>请联系管理员');
  });

  it.each([
    ['script', '<script>activePayload()</script>'],
    ['img onerror', '<img src="x" onerror="activePayload()">'],
    ['svg', '<svg onload="activePayload()"><script>activePayload()</script></svg>'],
    ['iframe', '<iframe srcdoc="<script>activePayload()</script>">activePayload()</iframe>'],
    ['object and embed', '<object data="javascript:activePayload()"></object><embed src="data:text/html,activePayload()">'],
    ['style', '<style>body{background:url(javascript:activePayload())}</style>'],
    ['math', '<math><mtext>activePayload()</mtext></math>'],
    ['template', '<template><img src="x" onerror="activePayload()">activePayload()</template>']
  ])('removes %s content from an import message', (_, payload) => {
    const { clean } = inspectMessage(`导入失败<br/>${payload}请检查账号`);

    expect(clean).toBe('导入失败<br>请检查账号');
  });

  it.each([
    ['javascript URL', 'javascript:activePayload()'],
    ['encoded javascript URL', 'jav&#x61;script:activePayload()'],
    ['data URL', 'data:text/html,<script>activePayload()</script>'],
    ['ordinary HTTPS URL', 'https://example.com/help']
  ])('removes links and all attributes even for %s', (_, href) => {
    const { clean } = inspectMessage(`账号 <a href="${href}" target="_blank" onclick="activePayload()">张三</a><br/>请检查`);

    expect(clean).toBe('账号 张三<br>请检查');
  });

  it.each([
    ['unclosed formatting', '<div><b>异常<br/><img src=x onerror=activePayload()></div>'],
    ['malformed svg', '<svg><g/onload=activePayload()//<p>异常<br>请重试'],
    ['mixed namespaces', '<math><mtext><table><mglyph><style><!--</style><img title="--><img src=x onerror=activePayload()>">异常<br>请重试'],
    ['misnested links', '<a href="javascript:activePayload()"><b>异常</a><br/>请重试']
  ])('retains only text and attribute-free br after parsing %s', (_, payload) => {
    const { clean } = inspectMessage(payload);

    expect(sanitizeImportMessage(clean)).toBe(clean);
  });

  it.each([null, undefined, 42, false, {}, []])('returns empty text for non-string input %#', (value) => {
    expect(inspectMessage(value).clean).toBe('');
  });

  it('returns empty text for an empty string', () => {
    expect(inspectMessage('').clean).toBe('');
  });

  it('does not coerce object input into HTML', () => {
    const toString = vi.fn(() => {
      throw new Error('Unexpected coercion of an import message');
    });

    expect(inspectMessage({ toString }).clean).toBe('');
    expect(toString).not.toHaveBeenCalled();
  });
});
