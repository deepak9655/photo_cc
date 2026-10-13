/** Tiny DOM helpers: no framework, no virtual DOM, no dependencies. */

type Attrs = Record<string, string | number | boolean | ((e: any) => void) | undefined | null>;

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Attrs = {},
  ...children: (Node | string | null | undefined)[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') {
      node.addEventListener(key.slice(2).toLowerCase(), value as EventListener);
    } else if (key === 'class') {
      node.className = String(value);
    } else if (key === 'html') {
      node.innerHTML = String(value);
    } else if (value === true) {
      node.setAttribute(key, '');
    } else {
      node.setAttribute(key, String(value));
    }
  }
  for (const child of children) {
    if (child === null || child === undefined) continue;
    node.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return node;
}

export function clear(node: HTMLElement): void {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function svg(path: string): SVGSVGElement {
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  node.setAttribute('viewBox', '0 0 24 24');
  node.innerHTML = path;
  return node;
}

export const ICONS = {
  eye: '<path d="M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6Z"/><circle cx="12" cy="12" r="2.6"/>',
  eyeOff: '<path d="M4 4l16 16"/><path d="M9.6 6.4A9.9 9.9 0 0 1 12 6c6.4 0 10 6 10 6a17 17 0 0 1-3.3 3.9M6.3 8.1A17 17 0 0 0 2 12s3.6 6 10 6a9.6 9.6 0 0 0 3.3-.6"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  unlock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 7.4-2"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  chevronDown: '<path d="m6 9 6 6 6-6"/>',
  group: '<path d="M4 7V5h16v2"/><rect x="4" y="7" width="16" height="12" rx="2"/>',
  text: '<path d="M5 6h14M12 6v13M9 19h6"/>',
  trash: '<path d="M5 7h14M10 11v6M14 11v6"/><path d="M6 7l1 13h10l1-13"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M15 9V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  up: '<path d="m6 14 6-6 6 6"/>',
  down: '<path d="m6 10 6 6 6-6"/>',
  image: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="8.5" cy="10" r="1.6"/><path d="m4 17 5-5 4 4 3-2 4 4"/>',
  wand: '<path d="M15 4v4M13 6h4M9 9l9 9-2 2-9-9z"/>',
};

export function iconBtn(iconPath: string, title: string, onClick: (e: any) => void, cls = 'icon-btn tiny'): HTMLButtonElement {
  const b = el('button', { class: cls, title, 'aria-label': title, onclick: onClick });
  b.append(svg(iconPath));
  return b;
}

/* --------------------------------------------------------------- toasts */

let toastHost: HTMLElement | null = null;
export function toast(message: string, kind: 'info' | 'warn' | 'good' = 'info', ms = 3200): void {
  if (!toastHost) toastHost = document.getElementById('toasts');
  if (!toastHost) return;
  const node = el('div', { class: `toast ${kind === 'info' ? '' : kind}` }, message);
  toastHost.append(node);
  setTimeout(() => {
    node.style.transition = 'opacity .2s ease';
    node.style.opacity = '0';
    setTimeout(() => node.remove(), 220);
  }, ms);
}

/* --------------------------------------------------------------- busy */

export function busy(text: string | null): void {
  const node = document.getElementById('busy');
  const label = document.getElementById('busy-text');
  if (!node) return;
  if (text === null) { node.hidden = true; return; }
  if (label) label.textContent = text;
  node.hidden = false;
}

/** Lets the browser paint the busy overlay before a long synchronous task. */
export function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
}

/* --------------------------------------------------------------- bottom sheet */

export interface SheetOptions {
  title: string;
  body: HTMLElement;
  actions?: HTMLElement[];
  onClose?(): void;
}

let openSheet: { node: HTMLElement; backdrop: HTMLElement } | null = null;

export function closeSheet(): void {
  if (!openSheet) return;
  openSheet.node.remove();
  openSheet.backdrop.remove();
  openSheet = null;
}

export function showSheet(options: SheetOptions): void {
  closeSheet();
  const body = el('div', { class: 'sheet-body' }, options.body);
  const sheet = el('div', { class: 'sheet', role: 'dialog', 'aria-label': options.title });
  const head = el('div', { class: 'sheet-head' },
    el('h2', {}, options.title),
    iconBtn('<path d="M6 6l12 12M18 6 6 18"/>', 'Close', () => closeSheet()),
  );
  sheet.append(el('div', { class: 'sheet-grip' }), head, body);
  if (options.actions?.length) {
    const row = el('div', { class: 'sheet-head', style: 'border-top:1px solid var(--line);border-bottom:none;gap:8px' }, ...options.actions);
    sheet.append(row);
  }
  const backdrop = el('div', {
    class: 'backdrop',
    onclick: () => { if (options.onClose) options.onClose(); closeSheet(); },
  });
  document.body.append(backdrop, sheet);
  openSheet = { node: sheet, backdrop };
}

export function confirmSheet(title: string, message: string, confirmLabel: string, onConfirm: () => void, danger = false): void {
  const body = el('div', {}, el('p', { style: 'margin:0;color:var(--fg-dim)' }, message));
  showSheet({
    title,
    body,
    actions: [
      el('button', { class: 'btn', style: 'flex:1', onclick: () => closeSheet() }, 'Cancel'),
      el('button', {
        class: `btn ${danger ? 'danger' : 'primary'}`, style: 'flex:1',
        onclick: () => { closeSheet(); onConfirm(); },
      }, confirmLabel),
    ],
  });
}

/* --------------------------------------------------------------- forms */

export interface FieldOptions {
  label: string;
  value: string;
  placeholder?: string;
  multiline?: boolean;
  onInput(value: string): void;
}

export function field(options: FieldOptions): HTMLElement {
  const input = options.multiline
    ? el('textarea', { placeholder: options.placeholder ?? '' })
    : el('input', { type: 'text', value: options.value, placeholder: options.placeholder ?? '' });
  (input as HTMLInputElement).value = options.value;
  input.addEventListener('input', () => options.onInput((input as HTMLInputElement).value));
  return el('div', { class: 'field' }, el('label', {}, options.label), input);
}

export interface SliderOptions {
  label: string;
  min: number;
  max: number;
  step: number;
  value: number;
  format?(v: number): string;
  onInput(v: number): void;
  /** Fired when the finger/mouse is released — used to close an undo step. */
  onCommit?(v: number): void;
}

export function slider(options: SliderOptions): HTMLElement {
  const valueNode = el('span', { class: 'value' }, options.format ? options.format(options.value) : String(options.value));
  const input = el('input', {
    type: 'range',
    min: options.min,
    max: options.max,
    step: options.step,
    value: options.value,
  });
  input.addEventListener('input', () => {
    const v = Number(input.value);
    valueNode.textContent = options.format ? options.format(v) : String(v);
    options.onInput(v);
  });
  if (options.onCommit) {
    const commit = () => options.onCommit!(Number(input.value));
    input.addEventListener('change', commit);
    input.addEventListener('pointerup', commit);
    input.addEventListener('touchend', commit);
  }
  return el('div', { class: 'field' },
    el('label', {}, options.label, valueNode),
    input,
  );
}

export function segmented<T extends string>(values: { value: T; label: string }[], current: T, onPick: (v: T) => void): HTMLElement {
  const wrap = el('div', { class: 'segmented' });
  for (const v of values) {
    const b = el('button', { 'aria-pressed': String(v.value === current), onclick: () => onPick(v.value) }, v.label);
    wrap.append(b);
  }
  return wrap;
}
