(function () {
  'use strict';
  const surfaces = new Map(), snapshots = new Map();
  window.DesignSurfaces = { register: (name, html, mount) => surfaces.set(name, { html, mount }) };
  customElements.define('sb-design-surface', class extends HTMLElement {
    connectedCallback() {
      const name = this.getAttribute('screen'), surface = surfaces.get(name);
      if (!surface) throw new Error(`Unknown design surface: ${name}`);
      const root = this.shadowRoot || this.attachShadow({ mode: 'open' });
      root.innerHTML = `<link rel="stylesheet" href="ds/surfaces.css"><link rel="stylesheet" href="ds/${name}.css"><div class="surface-body">${surface.html}</div>`;
      this.snapshotKey = `${name}/${this.getAttribute('work') || this.getAttribute('context') || ''}/${this.getAttribute('embedded') || ''}/${this.getAttribute('scenario') || ''}`;
      this.cleanup = [];
      const timers = new Set();
      const schedule = (fn, delay) => {
        const id = window.setTimeout(() => { timers.delete(id); fn(); }, delay);
        timers.add(id);
        return id;
      };
      this.cleanup.push(() => timers.forEach(window.clearTimeout));
      this.snapshot = surface.mount(root, this, this.cleanup, schedule, snapshots.get(this.snapshotKey));
      root.addEventListener('keydown', (event) => {
        if (event.key === 'Tab') return;
        if (this.getAttribute('embedded') === 'diff' && !event.target.matches('input,textarea') && ['j', 'k', '[', ']', 'a', 's', '/'].includes(event.key)) {
          event.preventDefault();
          this.dispatchEvent(new CustomEvent('design-change-key', { bubbles: true, composed: true, detail: { key: event.key } }));
        }
        if (event.key === 'Escape' && !event.target.matches('input,textarea')) this.dispatchEvent(new CustomEvent('design-escape', { bubbles: true, composed: true }));
        event.stopPropagation();
      });
    }
    disconnectedCallback() {
      if (this.snapshot) snapshots.set(this.snapshotKey, this.snapshot());
      (this.cleanup || []).forEach((dispose) => dispose());
    }
  });
})();
