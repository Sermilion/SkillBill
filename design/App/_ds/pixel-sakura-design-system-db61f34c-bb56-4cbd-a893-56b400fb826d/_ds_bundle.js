/* @ds-bundle: {"format":3,"namespace":"PixelSakuraDesignSystem_db61f3","components":[{"name":"ArticleBody","sourcePath":"components/content/ArticleBody.jsx"},{"name":"SakuraMark","sourcePath":"components/content/Brand.jsx"},{"name":"Brand","sourcePath":"components/content/Brand.jsx"},{"name":"Pagination","sourcePath":"components/content/Pagination.jsx"},{"name":"PostCard","sourcePath":"components/content/PostCard.jsx"},{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Chip","sourcePath":"components/core/Chip.jsx"},{"name":"Icon","sourcePath":"components/core/Icon.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"DataTable","sourcePath":"components/data/DataTable.jsx"},{"name":"Notice","sourcePath":"components/feedback/Notice.jsx"},{"name":"CheckboxGroup","sourcePath":"components/forms/CheckboxGroup.jsx"},{"name":"Field","sourcePath":"components/forms/Field.jsx"},{"name":"Select","sourcePath":"components/forms/Select.jsx"},{"name":"TextInput","sourcePath":"components/forms/TextInput.jsx"},{"name":"Textarea","sourcePath":"components/forms/Textarea.jsx"}],"sourceHashes":{"components/content/ArticleBody.jsx":"5153f5ee5ccb","components/content/Brand.jsx":"8863b938f8b5","components/content/Pagination.jsx":"5e74cda035cb","components/content/PostCard.jsx":"451c1339e15d","components/core/Badge.jsx":"b82eaca57371","components/core/Button.jsx":"5c41d481f2f7","components/core/Chip.jsx":"2f5228ad4afc","components/core/Icon.jsx":"2763f4c5a294","components/core/IconButton.jsx":"1ae5e2053fdf","components/data/DataTable.jsx":"6d94b12da002","components/feedback/Notice.jsx":"d91523591804","components/forms/CheckboxGroup.jsx":"4becda2dd462","components/forms/Field.jsx":"ec1c1dd953ff","components/forms/Select.jsx":"966436f6c467","components/forms/TextInput.jsx":"30b92482e18c","components/forms/Textarea.jsx":"2920fb32df53","ui_kits/admin-cms/kit.jsx":"1eca585f2682","ui_kits/public-site/kit.jsx":"c8496b1d206a"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.PixelSakuraDesignSystem_db61f3 = window.PixelSakuraDesignSystem_db61f3 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/content/ArticleBody.jsx
try { (() => {
/**
 * Article body — wraps server-rendered Markdown HTML in Pixel Sakura's
 * reading typography. Pass `html` (trusted, server-sanitized) or children.
 */
function ArticleBody({
  html,
  children,
  className = ""
}) {
  const cls = ["ps-article-body", className].filter(Boolean).join(" ");
  if (html != null) {
    return /*#__PURE__*/React.createElement("div", {
      className: cls,
      dangerouslySetInnerHTML: {
        __html: html
      }
    });
  }
  return /*#__PURE__*/React.createElement("div", {
    className: cls
  }, children);
}
Object.assign(__ds_scope, { ArticleBody });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/content/ArticleBody.jsx", error: String((e && e.message) || e) }); }

// components/content/Brand.jsx
try { (() => {
/** The Pixel Sakura sakura mark (5 petals + gold center). */
function SakuraMark({
  size = 28,
  className = ""
}) {
  const petal = "M16 16 C 11.5 11.5, 11.5 6, 16 2.8 C 20.5 6, 20.5 11.5, 16 16 Z";
  return /*#__PURE__*/React.createElement("svg", {
    width: size,
    height: size,
    viewBox: "0 0 32 32",
    className: className,
    "aria-hidden": "true",
    focusable: "false"
  }, /*#__PURE__*/React.createElement("g", {
    fill: "var(--color-primary, #FF4D8D)"
  }, [0, 72, 144, 216, 288].map(deg => /*#__PURE__*/React.createElement("path", {
    key: deg,
    d: petal,
    transform: `rotate(${deg} 16 16)`
  }))), /*#__PURE__*/React.createElement("circle", {
    cx: "16",
    cy: "16",
    r: "2.2",
    fill: "var(--color-accent, #F5B544)"
  }));
}

/**
 * Pixel Sakura brand lockup — sakura mark + wordmark.
 * Renders an <a> to home by default.
 */
function Brand({
  size = "md",
  invert = false,
  href = "/",
  className = ""
}) {
  const cls = ["ps-brand", size === "sm" ? "ps-brand--sm" : size === "lg" ? "ps-brand--lg" : "", invert ? "ps-brand--invert" : "", className].filter(Boolean).join(" ");
  const markSize = size === "lg" ? 40 : size === "sm" ? 22 : 28;
  return /*#__PURE__*/React.createElement("a", {
    href: href,
    className: cls,
    "aria-label": "Pixel Sakura \u2014 home"
  }, /*#__PURE__*/React.createElement(SakuraMark, {
    size: markSize,
    className: "ps-brand__mark"
  }), /*#__PURE__*/React.createElement("span", {
    className: "ps-brand__word"
  }, "Pixel ", /*#__PURE__*/React.createElement("b", null, "Sakura")));
}
Object.assign(__ds_scope, { SakuraMark, Brand });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/content/Brand.jsx", error: String((e && e.message) || e) }); }

// components/content/Pagination.jsx
try { (() => {
/**
 * Pagination — numbered pages with prev/next. Pass `current` (1-based) and
 * `total` page count; emits hrefs via `hrefFor(page)`.
 */
function Pagination({
  current = 1,
  total = 1,
  hrefFor = p => `?page=${p}`,
  className = ""
}) {
  if (total <= 1) return null;
  const pages = [];
  const push = p => pages.push(p);
  push(1);
  const from = Math.max(2, current - 1);
  const to = Math.min(total - 1, current + 1);
  if (from > 2) pages.push("…");
  for (let p = from; p <= to; p++) push(p);
  if (to < total - 1) pages.push("…");
  if (total > 1) push(total);
  return /*#__PURE__*/React.createElement("nav", {
    className: ["ps-pagination", className].filter(Boolean).join(" "),
    "aria-label": "Pagination"
  }, current > 1 ? /*#__PURE__*/React.createElement("a", {
    href: hrefFor(current - 1),
    rel: "prev",
    "aria-label": "Previous page"
  }, "\u2039") : null, pages.map((p, i) => p === "…" ? /*#__PURE__*/React.createElement("span", {
    key: `e${i}`,
    className: "ps-ellipsis",
    "aria-hidden": "true"
  }, "\u2026") : p === current ? /*#__PURE__*/React.createElement("span", {
    key: p,
    "aria-current": "page"
  }, p) : /*#__PURE__*/React.createElement("a", {
    key: p,
    href: hrefFor(p)
  }, p)), current < total ? /*#__PURE__*/React.createElement("a", {
    href: hrefFor(current + 1),
    rel: "next",
    "aria-label": "Next page"
  }, "\u203A") : null);
}
Object.assign(__ds_scope, { Pagination });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/content/Pagination.jsx", error: String((e && e.message) || e) }); }

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Status badge — shows post status (published / draft) with a leading dot.
 */
function Badge({
  status = "draft",
  children,
  className = "",
  ...rest
}) {
  const cls = ["ps-badge", `ps-badge--${status}`, className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("span", _extends({
    className: cls
  }, rest), children ?? (status === "published" ? "Published" : "Draft"));
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Pixel Sakura primary action button.
 * Renders a <button> by default, or an <a> when `href` is provided.
 */
function Button({
  variant = "primary",
  size = "md",
  block = false,
  loading = false,
  disabled = false,
  href,
  type = "button",
  icon = null,
  children,
  className = "",
  ...rest
}) {
  const cls = ["ps-btn", `ps-btn--${variant}`, size !== "md" ? `ps-btn--${size}` : "", block ? "ps-btn--block" : "", className].filter(Boolean).join(" ");
  const content = /*#__PURE__*/React.createElement(React.Fragment, null, icon ? /*#__PURE__*/React.createElement("span", {
    className: "ps-btn__icon",
    "aria-hidden": "true"
  }, icon) : null, children);
  if (href && !disabled) {
    return /*#__PURE__*/React.createElement("a", _extends({
      href: href,
      className: cls,
      "data-loading": loading || undefined
    }, rest), content);
  }
  return /*#__PURE__*/React.createElement("button", _extends({
    type: type,
    className: cls,
    disabled: disabled || loading,
    "data-loading": loading || undefined
  }, rest), content);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/Chip.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Taxonomy chip — classifies a post by type / platform / topic, or neutral.
 * Renders an <a> when `href` is set (clickable taxonomy link), else a <span>.
 */
function Chip({
  variant = "neutral",
  size = "md",
  href,
  children,
  className = "",
  ...rest
}) {
  const cls = ["ps-chip", `ps-chip--${variant}`, size === "sm" ? "ps-chip--sm" : "", className].filter(Boolean).join(" ");
  if (href) {
    return /*#__PURE__*/React.createElement("a", _extends({
      href: href,
      className: cls
    }, rest), children);
  }
  return /*#__PURE__*/React.createElement("span", _extends({
    className: cls
  }, rest), children);
}
Object.assign(__ds_scope, { Chip });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Chip.jsx", error: String((e && e.message) || e) }); }

// components/content/PostCard.jsx
try { (() => {
/**
 * Editorial post card — the core feed unit. Supports image / no-image,
 * a larger `lead` feature variant, and a compact horizontal `row` variant.
 */
function PostCard({
  variant = "default",
  href = "#",
  image,
  imageAlt = "",
  title,
  byline,
  date,
  excerpt,
  tags = [],
  className = ""
}) {
  const cls = ["ps-postcard", variant === "lead" ? "ps-postcard--lead" : "", variant === "row" ? "ps-postcard--row" : "", !image ? "ps-postcard--text" : "", className].filter(Boolean).join(" ");
  const meta = [byline, date].filter(Boolean).join(" · ");
  return /*#__PURE__*/React.createElement("article", {
    className: cls
  }, image ? /*#__PURE__*/React.createElement("a", {
    className: "ps-postcard__media",
    href: href,
    tabIndex: -1,
    "aria-hidden": "true"
  }, /*#__PURE__*/React.createElement("img", {
    src: image,
    alt: imageAlt,
    loading: "lazy"
  })) : null, /*#__PURE__*/React.createElement("div", {
    className: "ps-postcard__body"
  }, tags.length ? /*#__PURE__*/React.createElement("div", {
    className: "ps-chip-group"
  }, tags.map((t, i) => /*#__PURE__*/React.createElement(__ds_scope.Chip, {
    key: i,
    variant: t.variant || "neutral",
    size: "sm",
    href: t.href
  }, t.label))) : null, /*#__PURE__*/React.createElement("h3", {
    className: "ps-postcard__title"
  }, /*#__PURE__*/React.createElement("a", {
    href: href
  }, title)), meta ? /*#__PURE__*/React.createElement("p", {
    className: "ps-postcard__meta"
  }, meta) : null, excerpt && variant !== "row" ? /*#__PURE__*/React.createElement("p", {
    className: "ps-postcard__excerpt"
  }, excerpt) : null));
}
Object.assign(__ds_scope, { PostCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/content/PostCard.jsx", error: String((e && e.message) || e) }); }

// components/core/Icon.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Pixel Sakura icon — thin inline SVG set drawn from Lucide (MIT, 24px,
 * 2px stroke). Server-render friendly: no external font or CDN needed.
 * Add new glyphs by pasting the Lucide path body into PATHS below.
 */
const PATHS = {
  menu: "M4 6h16M4 12h16M4 18h16",
  search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16ZM21 21l-4.3-4.3",
  x: "M18 6 6 18M6 6l12 12",
  plus: "M12 5v14M5 12h14",
  check: "M20 6 9 17l-5-5",
  pencil: "M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z",
  trash: "M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6",
  upload: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12",
  image: "M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3ZM21 15l-5-5L5 21",
  eye: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
  "chevron-down": "M6 9l6 6 6-6",
  "chevron-right": "M9 6l6 6-6 6",
  "chevron-left": "M15 6l-6 6 6 6",
  "arrow-right": "M5 12h14M13 6l6 6-6 6",
  external: "M15 3h6v6M10 14 21 3M21 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5",
  calendar: "M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
  user: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z",
  tag: "M12.6 2.6 21 11a2 2 0 0 1 0 2.8l-7.2 7.2a2 2 0 0 1-2.8 0L2.6 12.6A2 2 0 0 1 2 11.2V4a2 2 0 0 1 2-2h7.2a2 2 0 0 1 1.4.6ZM7 7h.01",
  "alert-triangle": "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0ZM12 9v4M12 17h.01",
  "alert-circle": "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20ZM12 8v4M12 16h.01",
  "check-circle": "M22 11.1V12a10 10 0 1 1-5.9-9.1M22 4 12 14.01l-3-3",
  "log-out": "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
  settings: "M12.2 2h-.4a2 2 0 0 0-2 2 1.7 1.7 0 0 1-2.6 1.5 2 2 0 0 0-2.4.4l-.3.3a2 2 0 0 0-.4 2.4A1.7 1.7 0 0 1 2 12a2 2 0 0 0 0 .4 1.7 1.7 0 0 1 1.5 2.6 2 2 0 0 0 .4 2.4l.3.3a2 2 0 0 0 2.4.4A1.7 1.7 0 0 1 10 19.8a2 2 0 0 0 4 0 1.7 1.7 0 0 1 2.6-1.5 2 2 0 0 0 2.4-.4l.3-.3a2 2 0 0 0 .4-2.4A1.7 1.7 0 0 1 22 12a2 2 0 0 0 0-.4 1.7 1.7 0 0 1-1.5-2.6 2 2 0 0 0-.4-2.4l-.3-.3a2 2 0 0 0-2.4-.4A1.7 1.7 0 0 1 14 4.2 2 2 0 0 0 12.2 2ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
  layers: "M12 2 2 7l10 5 10-5-10-5ZM2 17l10 5 10-5M2 12l10 5 10-5",
  "file-text": "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8"
};
function Icon({
  name,
  size = 20,
  strokeWidth = 2,
  className = "",
  ...rest
}) {
  const d = PATHS[name];
  if (!d) return null;
  return /*#__PURE__*/React.createElement("svg", _extends({
    className: ["ps-icon", className].filter(Boolean).join(" "),
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: strokeWidth,
    strokeLinecap: "round",
    strokeLinejoin: "round",
    "aria-hidden": "true",
    focusable: "false"
  }, rest), d.split("M").filter(Boolean).map((seg, i) => /*#__PURE__*/React.createElement("path", {
    key: i,
    d: "M" + seg.trim()
  })));
}
Object.assign(__ds_scope, { Icon });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Icon.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Icon-only button for compact toolbars and table-row actions.
 * Always pass `label` for an accessible name.
 */
function IconButton({
  label,
  variant = "default",
  children,
  className = "",
  ...rest
}) {
  const cls = ["ps-icon-btn", variant === "danger" ? "ps-icon-btn--danger" : "", className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("button", _extends({
    type: "button",
    className: cls,
    "aria-label": label,
    title: label
  }, rest), children);
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/data/DataTable.jsx
try { (() => {
/**
 * Admin data table — dense, scannable, and responsive (stacks into labelled
 * cards on mobile). Define `columns` and pass `rows`; each column's `cell`
 * renders that row's content. Column `label` doubles as the mobile data-label.
 */
function DataTable({
  columns = [],
  rows = [],
  getRowKey,
  empty,
  className = ""
}) {
  if (!rows.length && empty) {
    return /*#__PURE__*/React.createElement("div", {
      className: ["ps-table-wrap", className].filter(Boolean).join(" ")
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-table-empty"
    }, empty));
  }
  return /*#__PURE__*/React.createElement("div", {
    className: ["ps-table-wrap", className].filter(Boolean).join(" ")
  }, /*#__PURE__*/React.createElement("table", {
    className: "ps-table"
  }, /*#__PURE__*/React.createElement("thead", null, /*#__PURE__*/React.createElement("tr", null, columns.map((c, i) => /*#__PURE__*/React.createElement("th", {
    key: i,
    className: c.align === "right" ? "ps-table__num" : ""
  }, c.label)))), /*#__PURE__*/React.createElement("tbody", null, rows.map((row, ri) => /*#__PURE__*/React.createElement("tr", {
    key: getRowKey ? getRowKey(row, ri) : ri
  }, columns.map((c, ci) => /*#__PURE__*/React.createElement("td", {
    key: ci,
    "data-label": c.label,
    className: c.align === "right" ? "ps-table__num" : ""
  }, c.cell(row))))))));
}
Object.assign(__ds_scope, { DataTable });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/data/DataTable.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Notice.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const ICONS = {
  success: "check-circle",
  error: "alert-circle",
  info: "alert-triangle",
  neutral: "file-text"
};

/**
 * Inline notice / alert banner for success, error, and info messages
 * (form results, htmx responses, "delete failed because tag in use").
 */
function Notice({
  variant = "info",
  children,
  className = "",
  ...rest
}) {
  const cls = ["ps-notice", `ps-notice--${variant}`, className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("div", _extends({
    className: cls,
    role: variant === "error" ? "alert" : "status"
  }, rest), /*#__PURE__*/React.createElement("span", {
    className: "ps-notice__icon"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: ICONS[variant] || ICONS.info,
    size: 18
  })), /*#__PURE__*/React.createElement("div", null, children));
}
Object.assign(__ds_scope, { Notice });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Notice.jsx", error: String((e && e.message) || e) }); }

// components/forms/CheckboxGroup.jsx
try { (() => {
/**
 * Checkbox (or radio) group — e.g. platform multi-select.
 * Pass options as [{value,label}]. Use `type="radio"` for single-select.
 */
function CheckboxGroup({
  name,
  options = [],
  value = [],
  type = "checkbox",
  onChange,
  className = ""
}) {
  const selected = Array.isArray(value) ? value : [value];
  return /*#__PURE__*/React.createElement("div", {
    className: ["ps-check-group", className].filter(Boolean).join(" "),
    role: "group"
  }, options.map(o => /*#__PURE__*/React.createElement("label", {
    key: o.value,
    className: "ps-check"
  }, /*#__PURE__*/React.createElement("input", {
    type: type,
    name: name,
    value: o.value,
    checked: selected.includes(o.value),
    onChange: onChange
  }), o.label)));
}
Object.assign(__ds_scope, { CheckboxGroup });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/CheckboxGroup.jsx", error: String((e && e.message) || e) }); }

// components/forms/Field.jsx
try { (() => {
/**
 * Form field wrapper — label, optional help text, and inline error,
 * wired with htmlFor / aria. Wrap any control as its child.
 */
function Field({
  label,
  htmlFor,
  required,
  help,
  error,
  children,
  className = ""
}) {
  const cls = ["ps-field", error ? "ps-field--invalid" : "", className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("div", {
    className: cls
  }, label ? /*#__PURE__*/React.createElement("label", {
    className: "ps-label",
    htmlFor: htmlFor
  }, label, required ? /*#__PURE__*/React.createElement("span", {
    className: "ps-required",
    "aria-hidden": "true"
  }, "*") : null) : null, children, help && !error ? /*#__PURE__*/React.createElement("p", {
    className: "ps-help"
  }, help) : null, error ? /*#__PURE__*/React.createElement("p", {
    className: "ps-error",
    role: "alert"
  }, error) : null);
}
Object.assign(__ds_scope, { Field });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Field.jsx", error: String((e && e.message) || e) }); }

// components/forms/Select.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Native select with the brand caret. Pass options as [{value,label}] or children. */
function Select({
  options,
  invalid = false,
  className = "",
  children,
  ...rest
}) {
  const cls = ["ps-select", className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("select", _extends({
    className: cls,
    "aria-invalid": invalid || undefined
  }, rest), options ? options.map(o => /*#__PURE__*/React.createElement("option", {
    key: o.value,
    value: o.value
  }, o.label)) : children);
}
Object.assign(__ds_scope, { Select });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Select.jsx", error: String((e && e.message) || e) }); }

// components/forms/TextInput.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Single-line text/password/number input. */
function TextInput({
  invalid = false,
  className = "",
  type = "text",
  ...rest
}) {
  const cls = ["ps-input", type === "number" ? "ps-input--number" : "", className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("input", _extends({
    type: type,
    className: cls,
    "aria-invalid": invalid || undefined
  }, rest));
}
Object.assign(__ds_scope, { TextInput });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/TextInput.jsx", error: String((e && e.message) || e) }); }

// components/forms/Textarea.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Multi-line textarea; use `code` for the Markdown body editor. */
function Textarea({
  code = false,
  invalid = false,
  rows = 6,
  className = "",
  ...rest
}) {
  const cls = ["ps-textarea", code ? "ps-textarea--code" : "", className].filter(Boolean).join(" ");
  return /*#__PURE__*/React.createElement("textarea", _extends({
    className: cls,
    rows: rows,
    "aria-invalid": invalid || undefined
  }, rest));
}
Object.assign(__ds_scope, { Textarea });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Textarea.jsx", error: String((e && e.message) || e) }); }

// ui_kits/admin-cms/kit.jsx
try { (() => {
/* Pixel Sakura — Admin CMS UI kit. Composes design-system components.
   Utilitarian publishing tool: login, dashboard, posts, editor, taxonomy, authors. */
(function () {
  const NS = window.PixelSakuraDesignSystem_db61f3;
  const {
    Brand,
    Button,
    IconButton,
    Icon,
    Badge,
    Chip,
    Field,
    TextInput,
    Textarea,
    Select,
    CheckboxGroup,
    DataTable,
    Notice,
    ArticleBody
  } = NS;
  const MARK = "<svg width='22' height='22' viewBox='0 0 32 32' aria-hidden='true'><g fill='#FF6FA3'>" + ["", "rotate(72 16 16)", "rotate(144 16 16)", "rotate(216 16 16)", "rotate(288 16 16)"].map(t => `<path d='M16 16 C 11.5 11.5, 11.5 6, 16 2.8 C 20.5 6, 20.5 11.5, 16 16 Z' transform='${t}'/>`).join("") + "</g><circle cx='16' cy='16' r='2.2' fill='#B38600'/></svg>";
  let POSTS = [{
    id: 1,
    title: "New JRPG trailer confirms global PC release window",
    status: "published",
    author: "Aoi Tanaka",
    updated: "2h ago"
  }, {
    id: 2,
    title: "Switch import roundup: this week's notable Asian releases",
    status: "draft",
    author: "Editorial desk",
    updated: "Yesterday"
  }, {
    id: 3,
    title: "Fighting game EVO lineup announced ahead of registration",
    status: "published",
    author: "K. Mori",
    updated: "Jun 15"
  }, {
    id: 4,
    title: "Visual novel studio teases a new branching mystery",
    status: "draft",
    author: "Pixel Sakura desk",
    updated: "Jun 14"
  }, {
    id: 5,
    title: "Platform update notes land ahead of the weekend patch",
    status: "published",
    author: "Editorial desk",
    updated: "Jun 14"
  }];

  // ---------- Login ----------
  function Login({
    go
  }) {
    const [err, setErr] = React.useState(false);
    return /*#__PURE__*/React.createElement("div", {
      className: "ps-auth"
    }, /*#__PURE__*/React.createElement("form", {
      className: "ps-auth__card",
      onSubmit: e => {
        e.preventDefault();
        const f = e.target;
        if (f.username.value === "admin" && f.password.value) {
          go("dashboard");
        } else setErr(true);
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-auth__brand"
    }, /*#__PURE__*/React.createElement("span", {
      dangerouslySetInnerHTML: {
        __html: MARK.replace("#FF6FA3", "#FF4D8D").replace(/22/g, '28')
      }
    }), /*#__PURE__*/React.createElement("span", {
      className: "ps-brand__word",
      style: {
        fontFamily: "var(--font-display)",
        fontSize: "20px",
        fontWeight: 700,
        letterSpacing: "-.02em"
      }
    }, "Pixel ", /*#__PURE__*/React.createElement("b", {
      style: {
        color: "var(--color-primary)"
      }
    }, "Sakura"))), /*#__PURE__*/React.createElement("p", {
      style: {
        textAlign: "center",
        color: "var(--color-text-muted)",
        fontSize: "14px",
        margin: "0 0 24px"
      }
    }, "Sign in to the editorial CMS"), err ? /*#__PURE__*/React.createElement("div", {
      style: {
        marginBottom: "16px"
      }
    }, /*#__PURE__*/React.createElement(Notice, {
      variant: "error"
    }, "Invalid username or password.")) : null, /*#__PURE__*/React.createElement(Field, {
      label: "Username",
      htmlFor: "username"
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "username",
      name: "username",
      defaultValue: "admin",
      autoComplete: "username"
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Password",
      htmlFor: "password"
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "password",
      name: "password",
      type: "password",
      placeholder: "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022",
      autoComplete: "current-password"
    })), /*#__PURE__*/React.createElement(Button, {
      variant: "primary",
      block: true,
      type: "submit"
    }, "Sign in"), /*#__PURE__*/React.createElement("p", {
      style: {
        textAlign: "center",
        fontSize: "12px",
        color: "var(--color-text-muted)",
        marginTop: "16px"
      }
    }, "Demo: username ", /*#__PURE__*/React.createElement("b", null, "admin"), " + any password")));
  }

  // ---------- Admin shell ----------
  function Shell({
    go,
    view,
    title,
    actions,
    children
  }) {
    const nav = [["dashboard", "Dashboard", "layers"], ["posts", "Posts", "file-text"], ["taxonomy", "Taxonomy", "tag"], ["authors", "Authors", "user"]];
    return /*#__PURE__*/React.createElement("div", {
      className: "ps-admin"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-admin__topbar"
    }, /*#__PURE__*/React.createElement("a", {
      className: "ps-admin__brand",
      href: "#",
      onClick: e => {
        e.preventDefault();
        go("dashboard");
      }
    }, /*#__PURE__*/React.createElement("span", {
      dangerouslySetInnerHTML: {
        __html: MARK
      }
    }), " Pixel Sakura ", /*#__PURE__*/React.createElement("span", {
      style: {
        opacity: .6,
        fontWeight: 500
      }
    }, "CMS")), /*#__PURE__*/React.createElement("nav", {
      style: {
        display: "flex",
        gap: "2px",
        marginLeft: "16px"
      }
    }, nav.map(([k, l]) => /*#__PURE__*/React.createElement("a", {
      key: k,
      href: "#",
      onClick: e => {
        e.preventDefault();
        go(k);
      },
      style: {
        padding: "6px 12px",
        borderRadius: "6px",
        fontSize: "14px",
        fontWeight: 600,
        color: view === k ? "#fff" : "rgba(255,255,255,0.7)",
        background: view === k ? "rgba(255,255,255,0.12)" : "transparent"
      }
    }, l))), /*#__PURE__*/React.createElement("div", {
      className: "ps-admin__user"
    }, /*#__PURE__*/React.createElement("span", null, /*#__PURE__*/React.createElement(Icon, {
      name: "user",
      size: 14
    }), " admin"), /*#__PURE__*/React.createElement("a", {
      href: "#",
      onClick: e => {
        e.preventDefault();
        go("login");
      },
      style: {
        display: "inline-flex",
        alignItems: "center",
        gap: "4px"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "log-out",
      size: 14
    }), " Sign out"))), /*#__PURE__*/React.createElement("div", {
      className: "ps-admin__main"
    }, title || actions ? /*#__PURE__*/React.createElement("div", {
      className: "ps-admin-head"
    }, /*#__PURE__*/React.createElement("h1", {
      className: "ps-admin-head__title"
    }, title), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        gap: "8px"
      }
    }, actions)) : null, children));
  }

  // ---------- Dashboard ----------
  function Dashboard({
    go
  }) {
    const tiles = [["posts", "Manage posts", "Write, edit, publish, and organize stories.", "file-text"], ["taxonomy", "Manage taxonomy", "Tag groups, public nav, and ordering.", "tag"], ["authors", "Manage authors", "Add writers and manage display names.", "user"], ["login", "Sign out", "End your editorial session.", "log-out"]];
    return /*#__PURE__*/React.createElement(Shell, {
      go: go,
      view: "dashboard",
      title: "Dashboard"
    }, /*#__PURE__*/React.createElement("p", {
      style: {
        color: "var(--color-text-muted)",
        marginTop: "-12px",
        marginBottom: "24px"
      }
    }, "Signed in as ", /*#__PURE__*/React.createElement("b", {
      style: {
        color: "var(--color-text)"
      }
    }, "admin"), ". Pick up where you left off."), /*#__PURE__*/React.createElement("div", {
      className: "ps-tiles"
    }, tiles.map(([k, t, d, ic]) => /*#__PURE__*/React.createElement("a", {
      key: k,
      className: "ps-tile",
      href: "#",
      onClick: e => {
        e.preventDefault();
        go(k);
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        color: "var(--color-primary)"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: ic,
      size: 22
    })), /*#__PURE__*/React.createElement("span", {
      className: "ps-tile__title"
    }, t), /*#__PURE__*/React.createElement("span", {
      className: "ps-tile__desc"
    }, d)))));
  }

  // ---------- Manage posts ----------
  function Posts({
    go
  }) {
    const [posts, setPosts] = React.useState(POSTS);
    const [confirm, setConfirm] = React.useState(null);
    const toggle = id => setPosts(ps => ps.map(p => p.id === id ? {
      ...p,
      status: p.status === "published" ? "draft" : "published",
      updated: "just now"
    } : p));
    const del = id => {
      setPosts(ps => ps.filter(p => p.id !== id));
      setConfirm(null);
    };
    return /*#__PURE__*/React.createElement(Shell, {
      go: go,
      view: "posts",
      title: "Manage posts",
      actions: /*#__PURE__*/React.createElement(Button, {
        variant: "primary",
        icon: /*#__PURE__*/React.createElement(Icon, {
          name: "plus",
          size: 16
        }),
        onClick: () => go("editor")
      }, "New post")
    }, /*#__PURE__*/React.createElement(DataTable, {
      rows: posts,
      getRowKey: p => p.id,
      empty: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
        className: "ps-table-empty__title"
      }, "No posts yet"), "Create your first story to get started."),
      columns: [{
        label: "Title",
        cell: p => /*#__PURE__*/React.createElement("span", {
          className: "ps-table__title"
        }, /*#__PURE__*/React.createElement("a", {
          href: "#",
          onClick: e => {
            e.preventDefault();
            go("editor");
          }
        }, p.title))
      }, {
        label: "Status",
        cell: p => /*#__PURE__*/React.createElement(Badge, {
          status: p.status
        })
      }, {
        label: "Author",
        cell: p => p.author
      }, {
        label: "Updated",
        cell: p => /*#__PURE__*/React.createElement("span", {
          className: "ps-table__sub"
        }, p.updated)
      }, {
        label: "Actions",
        align: "right",
        cell: p => /*#__PURE__*/React.createElement("div", {
          className: "ps-table__actions"
        }, /*#__PURE__*/React.createElement(Button, {
          size: "sm",
          variant: "secondary",
          onClick: () => go("editor")
        }, "Edit"), /*#__PURE__*/React.createElement(Button, {
          size: "sm",
          variant: "ghost",
          onClick: () => toggle(p.id)
        }, p.status === "published" ? "Unpublish" : "Publish"), /*#__PURE__*/React.createElement(Button, {
          size: "sm",
          variant: "danger",
          onClick: () => setConfirm(p)
        }, "Delete"))
      }]
    }), confirm ? /*#__PURE__*/React.createElement("div", {
      role: "dialog",
      "aria-modal": "true",
      style: {
        position: "fixed",
        inset: 0,
        background: "rgba(5,4,7,0.72)",
        backdropFilter: "blur(4px)",
        display: "grid",
        placeItems: "center",
        zIndex: 200,
        padding: "20px"
      },
      onClick: () => setConfirm(null)
    }, /*#__PURE__*/React.createElement("div", {
      onClick: e => e.stopPropagation(),
      style: {
        background: "var(--color-surface)",
        border: "1px solid var(--color-border-strong)",
        borderRadius: "20px",
        boxShadow: "var(--shadow-lg)",
        maxWidth: "420px",
        padding: "24px"
      }
    }, /*#__PURE__*/React.createElement("h2", {
      style: {
        fontFamily: "var(--font-display)",
        fontSize: "20px",
        fontWeight: 700,
        letterSpacing: "-.02em",
        marginBottom: "8px"
      }
    }, "Delete this post?"), /*#__PURE__*/React.createElement("p", {
      style: {
        color: "var(--color-text-muted)",
        fontSize: "14px",
        marginBottom: "20px"
      }
    }, "\u201C", confirm.title, "\u201D will be permanently removed. This can't be undone."), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        gap: "8px",
        justifyContent: "flex-end"
      }
    }, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      onClick: () => setConfirm(null)
    }, "Cancel"), /*#__PURE__*/React.createElement(Button, {
      variant: "danger",
      icon: /*#__PURE__*/React.createElement(Icon, {
        name: "trash",
        size: 16
      }),
      onClick: () => del(confirm.id)
    }, "Delete post")))) : null);
  }

  // ---------- Post editor (most important screen) ----------
  function Editor({
    go
  }) {
    const [title, setTitle] = React.useState("New JRPG trailer confirms global PC release window");
    const [slug, setSlug] = React.useState("");
    const [body, setBody] = React.useState("## What was actually confirmed\n\nThe publisher committed to a **simultaneous PC and console launch**, with a release window rather than a fixed date.\n\n- Worldwide PC release\n- Full EN / JP / KR text\n- Pre-order bonus set\n\n> The import scene kept this series visible for years.\n\n![Key art](/media/story.jpg)");
    const [platforms, setPlatforms] = React.useState(["pc", "ps5"]);
    const autoSlug = slug || title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 48);

    // tiny markdown → html for the live preview
    const html = React.useMemo(() => mdToHtml(body), [body]);
    const onPlat = e => setPlatforms(ps => e.target.checked ? [...ps, e.target.value] : ps.filter(v => v !== e.target.value));
    return /*#__PURE__*/React.createElement(Shell, {
      go: go,
      view: "posts",
      title: "Edit post",
      actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
        variant: "secondary",
        onClick: () => go("posts")
      }, "Cancel"), /*#__PURE__*/React.createElement(Button, {
        variant: "primary",
        icon: /*#__PURE__*/React.createElement(Icon, {
          name: "check",
          size: 16
        })
      }, "Save post"))
    }, /*#__PURE__*/React.createElement("div", {
      className: "editor-grid"
    }, /*#__PURE__*/React.createElement("form", {
      className: "ps-section",
      onSubmit: e => e.preventDefault(),
      style: {
        margin: 0
      }
    }, /*#__PURE__*/React.createElement(Field, {
      label: "Title",
      htmlFor: "e-title",
      required: true
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "e-title",
      value: title,
      onChange: e => setTitle(e.target.value)
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Slug",
      htmlFor: "e-slug",
      help: `Auto-generated if left blank · /${autoSlug}`
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "e-slug",
      value: slug,
      onChange: e => setSlug(e.target.value),
      placeholder: autoSlug
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Hero image URL",
      htmlFor: "e-hero",
      help: "Paste a URL, or upload below."
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "e-hero",
      placeholder: "https://\u2026/key-art.jpg"
    })), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        gap: "8px",
        marginTop: "-8px",
        marginBottom: "20px"
      }
    }, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      size: "sm",
      icon: /*#__PURE__*/React.createElement(Icon, {
        name: "upload",
        size: 15
      })
    }, "Upload hero"), /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      size: "sm",
      icon: /*#__PURE__*/React.createElement(Icon, {
        name: "image",
        size: 15
      })
    }, "Insert image")), /*#__PURE__*/React.createElement(Field, {
      label: "Body Markdown",
      htmlFor: "e-body"
    }, /*#__PURE__*/React.createElement(Textarea, {
      id: "e-body",
      code: true,
      rows: 12,
      value: body,
      onChange: e => setBody(e.target.value)
    })), /*#__PURE__*/React.createElement("div", {
      className: "editor-row"
    }, /*#__PURE__*/React.createElement(Field, {
      label: "Status",
      htmlFor: "e-status"
    }, /*#__PURE__*/React.createElement(Select, {
      id: "e-status",
      options: [{
        value: "draft",
        label: "Draft"
      }, {
        value: "published",
        label: "Published"
      }]
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Post type",
      htmlFor: "e-type"
    }, /*#__PURE__*/React.createElement(Select, {
      id: "e-type",
      options: [{
        value: "news",
        label: "News"
      }, {
        value: "review",
        label: "Review"
      }, {
        value: "article",
        label: "Article"
      }]
    }))), /*#__PURE__*/React.createElement(Field, {
      label: "Platforms"
    }, /*#__PURE__*/React.createElement(CheckboxGroup, {
      name: "platform",
      value: platforms,
      onChange: onPlat,
      options: [{
        value: "ps5",
        label: "PS5"
      }, {
        value: "switch",
        label: "Switch"
      }, {
        value: "pc",
        label: "PC"
      }, {
        value: "xbox",
        label: "Xbox"
      }]
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Topics",
      htmlFor: "e-topics",
      help: "Comma-separated. Suggestions appear as you type."
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "e-topics",
      defaultValue: "JRPG, trailers"
    }))), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: "8px",
        marginBottom: "10px"
      }
    }, /*#__PURE__*/React.createElement("span", {
      className: "ps-label",
      style: {
        margin: 0
      }
    }, "Live preview"), /*#__PURE__*/React.createElement("span", {
      style: {
        display: "inline-flex",
        alignItems: "center",
        gap: "4px",
        fontSize: "12px",
        color: "var(--color-text-muted)"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "eye",
      size: 14
    }), " updates as you type")), /*#__PURE__*/React.createElement("div", {
      className: "ps-section",
      style: {
        margin: 0,
        background: "var(--color-bg)"
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-chip-group",
      style: {
        marginBottom: "12px"
      }
    }, /*#__PURE__*/React.createElement(Chip, {
      variant: "type"
    }, "News"), platforms.map(p => /*#__PURE__*/React.createElement(Chip, {
      key: p,
      variant: "platform"
    }, p.toUpperCase())), /*#__PURE__*/React.createElement(Chip, {
      variant: "topic"
    }, "JRPG")), /*#__PURE__*/React.createElement("h2", {
      style: {
        fontFamily: "var(--font-display)",
        fontSize: "30px",
        lineHeight: "36px",
        fontWeight: 700,
        letterSpacing: "-.025em",
        textWrap: "balance",
        marginBottom: "8px"
      }
    }, title || "Untitled story"), /*#__PURE__*/React.createElement("p", {
      style: {
        color: "var(--color-text-muted)",
        fontSize: "13px",
        marginBottom: "16px"
      }
    }, "By Aoi Tanaka \xB7 June 16, 2026"), /*#__PURE__*/React.createElement("div", {
      className: "ps-article-hero",
      style: {
        aspectRatio: "16/8",
        marginBottom: "16px"
      }
    }, /*#__PURE__*/React.createElement("img", {
      alt: "",
      src: "data:image/svg+xml," + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' width='560' height='280'><defs><radialGradient id='gg' cx='72%' cy='34%' r='60%'><stop offset='0' stop-color='#FF4D8D' stop-opacity='0.7'/><stop offset='1' stop-color='#FF4D8D' stop-opacity='0'/></radialGradient></defs><rect width='560' height='280' fill='#1B1320'/><rect width='560' height='280' fill='url(#gg)'/><circle cx='405' cy='96' r='26' fill='#FFCE5C'/></svg>")
    })), /*#__PURE__*/React.createElement(ArticleBody, {
      html: html
    })))));
  }
  function mdToHtml(md) {
    const esc = s => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const lines = md.split("\n");
    let out = [];
    let i = 0;
    while (i < lines.length) {
      let l = lines[i];
      if (/^!\[.*\]\(.*\)/.test(l)) {
        out.push("<figure><img alt='' src=\"data:image/svg+xml," + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' width='520' height='240'><defs><radialGradient id='gf' cx='68%' cy='38%' r='60%'><stop offset='0' stop-color='#FF4D8D' stop-opacity='0.6'/><stop offset='1' stop-color='#FF4D8D' stop-opacity='0'/></radialGradient></defs><rect width='520' height='240' fill='#161019'/><rect width='520' height='240' fill='url(#gf)'/><circle cx='350' cy='96' r='22' fill='#FFCE5C'/></svg>") + "\"/><figcaption>Screenshot</figcaption></figure>");
        i++;
        continue;
      }
      if (/^### /.test(l)) {
        out.push("<h3>" + esc(l.slice(4)) + "</h3>");
        i++;
        continue;
      }
      if (/^## /.test(l)) {
        out.push("<h2>" + esc(l.slice(3)) + "</h2>");
        i++;
        continue;
      }
      if (/^> /.test(l)) {
        out.push("<blockquote>" + inline(esc(l.slice(2))) + "</blockquote>");
        i++;
        continue;
      }
      if (/^- /.test(l)) {
        let items = [];
        while (i < lines.length && /^- /.test(lines[i])) {
          items.push("<li>" + inline(esc(lines[i].slice(2))) + "</li>");
          i++;
        }
        out.push("<ul>" + items.join("") + "</ul>");
        continue;
      }
      if (l.trim() === "") {
        i++;
        continue;
      }
      out.push("<p>" + inline(esc(l)) + "</p>");
      i++;
    }
    function inline(s) {
      return s.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>").replace(/\[(.+?)\]\((.+?)\)/g, "<a href='#'>$1</a>");
    }
    return out.join("");
  }

  // ---------- Taxonomy ----------
  function Taxonomy({
    go
  }) {
    const groups = [{
      name: "Post type",
      nav: "Yes",
      tags: [["News", "news"], ["Review", "review"], ["Article", "article"]]
    }, {
      name: "Platform",
      nav: "Yes",
      tags: [["PS5", "ps5"], ["Switch", "switch"], ["PC", "pc"], ["Xbox", "xbox"]]
    }, {
      name: "Topic",
      nav: "No",
      tags: [["JRPG", "jrpg"], ["Visual novel", "visual-novel"], ["Fighting games", "fighting"]]
    }];
    return /*#__PURE__*/React.createElement(Shell, {
      go: go,
      view: "taxonomy",
      title: "Manage taxonomy"
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        marginBottom: "20px"
      }
    }, /*#__PURE__*/React.createElement(Notice, {
      variant: "success"
    }, "Navigation updated for \u201CPost type\u201D.")), groups.map((g, gi) => /*#__PURE__*/React.createElement("section", {
      className: "ps-section",
      key: gi
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-section__head"
    }, /*#__PURE__*/React.createElement("h2", {
      className: "ps-section__title"
    }, g.name), /*#__PURE__*/React.createElement("form", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: "8px"
      },
      onSubmit: e => e.preventDefault()
    }, /*#__PURE__*/React.createElement("label", {
      className: "ps-label",
      style: {
        margin: 0
      }
    }, "Show in public nav"), /*#__PURE__*/React.createElement(Select, {
      defaultValue: g.nav === "Yes" ? "yes" : "no",
      options: [{
        value: "yes",
        label: "Yes"
      }, {
        value: "no",
        label: "No"
      }],
      style: {
        width: "90px",
        height: "36px"
      }
    }), /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "Save nav"))), /*#__PURE__*/React.createElement("form", {
      className: "tax-add",
      onSubmit: e => e.preventDefault()
    }, /*#__PURE__*/React.createElement(TextInput, {
      placeholder: `New ${g.name.toLowerCase()} tag name`,
      "aria-label": "New tag name"
    }), /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      icon: /*#__PURE__*/React.createElement(Icon, {
        name: "plus",
        size: 15
      })
    }, "Add tag")), /*#__PURE__*/React.createElement("div", {
      className: "ps-table-wrap",
      style: {
        marginTop: "16px"
      }
    }, /*#__PURE__*/React.createElement("table", {
      className: "ps-table"
    }, /*#__PURE__*/React.createElement("thead", null, /*#__PURE__*/React.createElement("tr", null, /*#__PURE__*/React.createElement("th", {
      style: {
        width: "56px"
      }
    }, "Order"), /*#__PURE__*/React.createElement("th", null, "Name"), /*#__PURE__*/React.createElement("th", null, "Slug"), /*#__PURE__*/React.createElement("th", {
      style: {
        textAlign: "right"
      }
    }, "Actions"))), /*#__PURE__*/React.createElement("tbody", null, g.tags.map(([name, slug], ti) => /*#__PURE__*/React.createElement("tr", {
      key: slug
    }, /*#__PURE__*/React.createElement("td", {
      "data-label": "Order"
    }, /*#__PURE__*/React.createElement("input", {
      className: "ps-input ps-input--number",
      defaultValue: ti + 1,
      "aria-label": "Sort order"
    })), /*#__PURE__*/React.createElement("td", {
      "data-label": "Name"
    }, /*#__PURE__*/React.createElement("input", {
      className: "ps-input",
      defaultValue: name,
      "aria-label": "Tag name",
      style: {
        maxWidth: "260px"
      }
    })), /*#__PURE__*/React.createElement("td", {
      "data-label": "Slug"
    }, /*#__PURE__*/React.createElement("span", {
      className: "ps-table__sub",
      style: {
        fontFamily: "var(--font-mono)"
      }
    }, "/", slug)), /*#__PURE__*/React.createElement("td", {
      "data-label": "Actions",
      style: {
        textAlign: "right"
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-table__actions"
    }, /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "Rename"), /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "danger"
    }, "Delete")))))))))));
  }

  // ---------- Authors ----------
  function Authors({
    go
  }) {
    const authors = [["aoi", "Aoi Tanaka"], ["kmori", "K. Mori"], ["desk", "Pixel Sakura desk"]];
    return /*#__PURE__*/React.createElement(Shell, {
      go: go,
      view: "authors",
      title: "Manage authors"
    }, /*#__PURE__*/React.createElement("div", {
      className: "authors-grid"
    }, /*#__PURE__*/React.createElement("section", {
      className: "ps-section",
      style: {
        margin: 0
      }
    }, /*#__PURE__*/React.createElement("h2", {
      className: "ps-section__title",
      style: {
        marginBottom: "16px"
      }
    }, "Existing authors"), /*#__PURE__*/React.createElement("div", {
      className: "ps-table-wrap"
    }, /*#__PURE__*/React.createElement("table", {
      className: "ps-table"
    }, /*#__PURE__*/React.createElement("thead", null, /*#__PURE__*/React.createElement("tr", null, /*#__PURE__*/React.createElement("th", null, "Username"), /*#__PURE__*/React.createElement("th", null, "Display name"))), /*#__PURE__*/React.createElement("tbody", null, authors.map(([u, n]) => /*#__PURE__*/React.createElement("tr", {
      key: u
    }, /*#__PURE__*/React.createElement("td", {
      "data-label": "Username"
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        fontFamily: "var(--font-mono)",
        fontSize: "13px"
      }
    }, u)), /*#__PURE__*/React.createElement("td", {
      "data-label": "Display name"
    }, n))))))), /*#__PURE__*/React.createElement("section", {
      className: "ps-section",
      style: {
        margin: 0
      }
    }, /*#__PURE__*/React.createElement("h2", {
      className: "ps-section__title",
      style: {
        marginBottom: "16px"
      }
    }, "Create author"), /*#__PURE__*/React.createElement("div", {
      style: {
        marginBottom: "16px"
      }
    }, /*#__PURE__*/React.createElement(Notice, {
      variant: "success"
    }, "Author \u201CK. Mori\u201D created.")), /*#__PURE__*/React.createElement("form", {
      onSubmit: e => e.preventDefault()
    }, /*#__PURE__*/React.createElement(Field, {
      label: "Username",
      htmlFor: "a-user",
      required: true
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "a-user",
      placeholder: "lowercase-handle"
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Display name",
      htmlFor: "a-name",
      required: true
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "a-name",
      placeholder: "Full name"
    })), /*#__PURE__*/React.createElement(Field, {
      label: "Initial password",
      htmlFor: "a-pass",
      required: true,
      help: "The author can change this after first sign-in."
    }, /*#__PURE__*/React.createElement(TextInput, {
      id: "a-pass",
      type: "password",
      placeholder: "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
    })), /*#__PURE__*/React.createElement(Button, {
      variant: "primary",
      type: "submit"
    }, "Create author")))));
  }
  function App() {
    const [view, setView] = React.useState("dashboard");
    const go = v => {
      setView(v);
      window.scrollTo(0, 0);
    };
    let screen;
    if (view === "login") screen = /*#__PURE__*/React.createElement(Login, {
      go: go
    });else if (view === "dashboard") screen = /*#__PURE__*/React.createElement(Dashboard, {
      go: go
    });else if (view === "posts") screen = /*#__PURE__*/React.createElement(Posts, {
      go: go
    });else if (view === "editor") screen = /*#__PURE__*/React.createElement(Editor, {
      go: go
    });else if (view === "taxonomy") screen = /*#__PURE__*/React.createElement(Taxonomy, {
      go: go
    });else screen = /*#__PURE__*/React.createElement(Authors, {
      go: go
    });
    return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
      key: view
    }, screen), /*#__PURE__*/React.createElement(Switcher, {
      view: view,
      setView: go
    }));
  }
  function Switcher({
    view,
    setView
  }) {
    const items = [["login", "Login"], ["dashboard", "Dashboard"], ["posts", "Posts"], ["editor", "Editor"], ["taxonomy", "Taxonomy"], ["authors", "Authors"]];
    return /*#__PURE__*/React.createElement("div", {
      style: {
        position: "fixed",
        bottom: "16px",
        left: "50%",
        transform: "translateX(-50%)",
        display: "flex",
        gap: "4px",
        flexWrap: "wrap",
        justifyContent: "center",
        maxWidth: "94vw",
        background: "var(--color-surface)",
        border: "1px solid var(--color-border)",
        borderRadius: "999px",
        padding: "4px",
        boxShadow: "var(--shadow-md)",
        zIndex: 300
      }
    }, items.map(([k, l]) => /*#__PURE__*/React.createElement("button", {
      key: k,
      onClick: () => setView(k),
      style: {
        border: 0,
        cursor: "pointer",
        borderRadius: "999px",
        padding: "6px 14px",
        fontSize: "13px",
        fontWeight: 600,
        background: view === k ? "var(--color-primary)" : "transparent",
        color: view === k ? "#fff" : "var(--color-text-muted)"
      }
    }, l)));
  }
  window.PSAdminKit = {
    App
  };
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/admin-cms/kit.jsx", error: String((e && e.message) || e) }); }

// ui_kits/public-site/kit.jsx
try { (() => {
/* Pixel Sakura — Public site UI kit (header, footer, screens, sample data).
   Composes the design-system components from window.PixelSakuraDesignSystem_db61f3. */
(function () {
  const NS = window.PixelSakuraDesignSystem_db61f3;
  const {
    Brand,
    PostCard,
    Chip,
    Pagination,
    ArticleBody,
    Button,
    Icon
  } = NS;

  // ---- Placeholder key art (dark base + neon sakura/gold glow) ----
  function keyart(a, b, w, h) {
    const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='${w}' height='${h}'>` + `<defs>` + `<linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>` + `<stop offset='0' stop-color='${a}'/><stop offset='1' stop-color='${b}'/></linearGradient>` + `<radialGradient id='glow' cx='74%' cy='32%' r='55%'>` + `<stop offset='0' stop-color='#FF4D8D' stop-opacity='0.65'/>` + `<stop offset='0.55' stop-color='#FF4D8D' stop-opacity='0.12'/>` + `<stop offset='1' stop-color='#FF4D8D' stop-opacity='0'/></radialGradient></defs>` + `<rect width='${w}' height='${h}' fill='url(#g)'/>` + `<rect width='${w}' height='${h}' fill='url(#glow)'/>` + `<circle cx='${w * 0.74}' cy='${h * 0.32}' r='${h * 0.10}' fill='#FFCE5C' opacity='0.9'/>` + `<g stroke='#FF9CC0' stroke-width='1.5' opacity='0.25' fill='none'>` + `<path d='M0 ${h * 0.82} Q ${w * 0.3} ${h * 0.6} ${w * 0.55} ${h * 0.78} T ${w} ${h * 0.68}'/></g>` + `<rect x='0' y='${h * 0.62}' width='${w}' height='${h * 0.38}' fill='#0D0B11' opacity='0.42'/></svg>`;
    return "data:image/svg+xml," + encodeURIComponent(svg);
  }
  const T = {
    news: {
      label: "News",
      variant: "type",
      href: "/news"
    },
    review: {
      label: "Review",
      variant: "type",
      href: "/review"
    },
    article: {
      label: "Article",
      variant: "type",
      href: "/article"
    },
    ps5: {
      label: "PS5",
      variant: "platform",
      href: "/platform/ps5"
    },
    switch: {
      label: "Switch",
      variant: "platform",
      href: "/platform/switch"
    },
    pc: {
      label: "PC",
      variant: "platform",
      href: "/platform/pc"
    },
    jrpg: {
      label: "JRPG",
      variant: "topic",
      href: "/topic/jrpg"
    },
    vn: {
      label: "Visual novel",
      variant: "topic",
      href: "/topic/visual-novel"
    },
    fg: {
      label: "Fighting",
      variant: "topic",
      href: "/topic/fighting"
    }
  };
  const POSTS = [{
    id: 1,
    title: "New JRPG trailer confirms global PC release window",
    byline: "Aoi Tanaka",
    date: "June 16, 2026",
    excerpt: "The publisher closed its summer showcase with a surprise: a worldwide PC launch alongside consoles, ending months of import-only speculation.",
    art: ["#1B1320", "#2A1320"],
    tags: [T.news, T.pc, T.jrpg]
  }, {
    id: 2,
    title: "Switch import roundup: this week's notable Asian releases",
    byline: "Editorial desk",
    date: "June 15, 2026",
    excerpt: "Five region-locked titles worth importing, plus which ones already have a confirmed localization on the way.",
    art: ["#141019", "#201826"],
    tags: [T.article, T.switch]
  }, {
    id: 3,
    title: "Fighting game EVO lineup announced ahead of registration",
    byline: "K. Mori",
    date: "June 15, 2026",
    excerpt: "The main stage roster leans on long-running Asian franchises, with one notable newcomer making its tournament debut.",
    art: ["#1E1018", "#2C1526"],
    tags: [T.news, T.ps5, T.fg]
  }, {
    id: 4,
    title: "Visual novel studio teases a new branching mystery",
    byline: "Pixel Sakura desk",
    date: "June 14, 2026",
    excerpt: "A short teaser site went live overnight with a countdown and a single piece of character key art.",
    art: ["#181221", "#22182B"],
    tags: [T.news, T.pc, T.vn]
  }, {
    id: 5,
    title: "Platform update notes land ahead of the weekend patch",
    byline: "Editorial desk",
    date: "June 14, 2026",
    excerpt: "System-level changes touch download queueing and friends presence; here's what actually changes for players.",
    art: null,
    tags: [T.news, T.ps5]
  }, {
    id: 6,
    title: "Monster Hunter event date leaks ahead of showcase",
    byline: "Desk",
    date: "June 13, 2026",
    excerpt: "Storefront metadata briefly listed the collaboration window before being pulled.",
    art: ["#1C1119", "#281622"],
    tags: [T.news, T.switch]
  }, {
    id: 7,
    title: "Import review: a stylish action RPG worth the wait",
    byline: "Aoi Tanaka",
    date: "June 12, 2026",
    excerpt: "Gorgeous art direction and a tight combat loop carry a story that takes a while to find its footing.",
    art: ["#171120", "#241526"],
    tags: [T.review, T.pc, T.jrpg]
  }];
  function img(p, w, h) {
    return p.art ? keyart(p.art[0], p.art[1], w, h) : undefined;
  }

  // ---------- Header ----------
  function Header({
    go,
    current
  }) {
    const [open, setOpen] = React.useState(false);
    const nav = [["Home", "home"], ["News", "news"], ["Reviews", "review"], ["PC", "pc"], ["Switch", "switch"], ["PS5", "ps5"]];
    return /*#__PURE__*/React.createElement("header", {
      className: "ps-header"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-container ps-header__bar"
    }, /*#__PURE__*/React.createElement("a", {
      className: "ps-brand",
      href: "#",
      onClick: e => {
        e.preventDefault();
        go("home");
      },
      "aria-label": "Pixel Sakura \u2014 home"
    }, /*#__PURE__*/React.createElement("span", {
      className: "ps-brand__mark",
      dangerouslySetInnerHTML: {
        __html: MARK
      }
    }), /*#__PURE__*/React.createElement("span", {
      className: "ps-brand__word"
    }, "Pixel ", /*#__PURE__*/React.createElement("b", null, "Sakura"))), /*#__PURE__*/React.createElement("button", {
      className: "ps-icon-btn ps-nav-toggle",
      "aria-label": "Menu",
      "aria-expanded": open,
      onClick: () => setOpen(o => !o)
    }, /*#__PURE__*/React.createElement(Icon, {
      name: open ? "x" : "menu"
    })), /*#__PURE__*/React.createElement("nav", {
      className: "ps-nav",
      hidden: false,
      "data-open": open
    }, nav.map(([label, key]) => /*#__PURE__*/React.createElement("a", {
      key: key,
      href: "#",
      "aria-current": current === key ? "page" : undefined,
      onClick: e => {
        e.preventDefault();
        go(key);
        setOpen(false);
      }
    }, label)))));
  }
  const MARK = "<svg width='28' height='28' viewBox='0 0 32 32' aria-hidden='true'><g fill='#8C1D40'>" + ["", "rotate(72 16 16)", "rotate(144 16 16)", "rotate(216 16 16)", "rotate(288 16 16)"].map(t => `<path d='M16 16 C 11.5 11.5, 11.5 6, 16 2.8 C 20.5 6, 20.5 11.5, 16 16 Z' transform='${t}'/>`).join("") + "</g><circle cx='16' cy='16' r='2.2' fill='#B38600'/></svg>";
  function Footer() {
    return /*#__PURE__*/React.createElement("footer", {
      className: "ps-footer"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-container ps-footer__inner"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-footer__tagline"
    }, "Pixel Sakura \u2014 daily news, reviews, and imports from the Asian PC & console scene."), /*#__PURE__*/React.createElement("div", {
      className: "ps-footer__links"
    }, /*#__PURE__*/React.createElement("a", {
      href: "#"
    }, "About"), /*#__PURE__*/React.createElement("a", {
      href: "#"
    }, "RSS"), /*#__PURE__*/React.createElement("a", {
      href: "#"
    }, "Contact"))));
  }

  // ---------- Home feed ----------
  function HomeFeed({
    go,
    empty
  }) {
    const [lead, ...rest] = POSTS;
    const tick = rest.slice(0, 4);
    const grid = rest.slice(0);
    if (empty) {
      return /*#__PURE__*/React.createElement("main", {
        className: "ps-container"
      }, /*#__PURE__*/React.createElement(SectionHead, {
        eyebrow: "The feed",
        title: "Latest stories"
      }), /*#__PURE__*/React.createElement("div", {
        className: "ps-empty"
      }, /*#__PURE__*/React.createElement("div", {
        className: "ps-empty__mark",
        "aria-hidden": "true",
        dangerouslySetInnerHTML: {
          __html: MARK.replace(/28/g, '52')
        }
      }), /*#__PURE__*/React.createElement("div", {
        className: "ps-empty__title"
      }, "No published stories yet"), /*#__PURE__*/React.createElement("p", {
        className: "ps-empty__desc"
      }, "When the desk publishes its first story, it will appear here at the top of the feed."), /*#__PURE__*/React.createElement(Button, {
        variant: "primary"
      }, "Browse the archive")));
    }
    return /*#__PURE__*/React.createElement("main", {
      className: "ps-container"
    }, /*#__PURE__*/React.createElement(SectionHead, {
      eyebrow: "Today on Pixel Sakura",
      title: "Latest stories"
    }), /*#__PURE__*/React.createElement("div", {
      className: "ps-hero"
    }, /*#__PURE__*/React.createElement(Click, {
      go: go
    }, /*#__PURE__*/React.createElement(PostCard, {
      variant: "lead",
      image: img(lead, 860, 520),
      title: lead.title,
      byline: lead.byline,
      date: lead.date,
      excerpt: lead.excerpt,
      tags: lead.tags
    })), /*#__PURE__*/React.createElement("aside", {
      className: "ps-ticker"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-ticker__head"
    }, /*#__PURE__*/React.createElement("span", {
      className: "ps-eyebrow"
    }, "Latest drops")), tick.map((p, i) => /*#__PURE__*/React.createElement("div", {
      className: "ps-ticker__item",
      key: p.id
    }, /*#__PURE__*/React.createElement("span", {
      className: "ps-ticker__num"
    }, String(i + 1).padStart(2, "0")), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
      className: "ps-ticker__title"
    }, /*#__PURE__*/React.createElement("a", {
      href: "#",
      onClick: e => {
        e.preventDefault();
        go("article");
      }
    }, p.title)), /*#__PURE__*/React.createElement("div", {
      className: "ps-ticker__meta"
    }, p.tags[0].label, " \xB7 ", p.date)))))), /*#__PURE__*/React.createElement(SectionHead, {
      eyebrow: "More stories",
      title: "Across every platform",
      rule: true
    }), /*#__PURE__*/React.createElement("div", {
      className: "ps-feed"
    }, grid.map(p => /*#__PURE__*/React.createElement(Click, {
      key: p.id,
      go: go
    }, /*#__PURE__*/React.createElement(PostCard, {
      image: img(p, 400, 225),
      title: p.title,
      byline: p.byline,
      date: p.date,
      excerpt: p.excerpt,
      tags: p.tags
    })))), /*#__PURE__*/React.createElement(Pagination, {
      current: 1,
      total: 6,
      hrefFor: () => "#"
    }));
  }

  // Section header with eyebrow + display title (+ optional accent rule)
  function SectionHead({
    eyebrow,
    title,
    rule
  }) {
    return /*#__PURE__*/React.createElement("div", {
      style: {
        padding: rule ? "var(--space-10) 0 var(--space-6)" : "var(--space-10) 0 var(--space-6)",
        borderTop: rule ? "1px solid var(--color-border)" : "none",
        marginTop: rule ? "var(--space-8)" : 0
      }
    }, eyebrow ? /*#__PURE__*/React.createElement("div", {
      className: "ps-eyebrow",
      style: {
        marginBottom: "12px"
      }
    }, eyebrow) : null, /*#__PURE__*/React.createElement("h1", {
      className: "ps-page-head__title",
      style: {
        margin: 0
      }
    }, title));
  }

  // Wrap a card so clicking the title routes to the article view
  function Click({
    go,
    children
  }) {
    return /*#__PURE__*/React.createElement("div", {
      onClick: e => {
        if (e.target.closest('a')) {
          e.preventDefault();
          go('article');
        }
      }
    }, children);
  }

  // ---------- Taxonomy ----------
  function Taxonomy({
    go,
    name,
    desc
  }) {
    const list = POSTS.slice(1);
    return /*#__PURE__*/React.createElement("main", {
      className: "ps-container"
    }, /*#__PURE__*/React.createElement("div", {
      className: "ps-page-head ps-page-head--tax"
    }, /*#__PURE__*/React.createElement("h1", {
      className: "ps-page-head__title"
    }, name), /*#__PURE__*/React.createElement("span", {
      className: "ps-chip ps-chip--neutral"
    }, list.length, " stories")), desc ? /*#__PURE__*/React.createElement("p", {
      className: "ps-page-head__desc",
      style: {
        marginTop: "-12px",
        marginBottom: "24px"
      }
    }, desc) : null, /*#__PURE__*/React.createElement("div", {
      className: "ps-feed"
    }, list.map(p => /*#__PURE__*/React.createElement(Click, {
      key: p.id,
      go: go
    }, /*#__PURE__*/React.createElement(PostCard, {
      image: img(p, 360, 200),
      title: p.title,
      byline: p.byline,
      date: p.date,
      excerpt: p.excerpt,
      tags: p.tags
    })))), /*#__PURE__*/React.createElement(Pagination, {
      current: 2,
      total: 5,
      hrefFor: () => "#"
    }));
  }

  // ---------- Article ----------
  function Article({
    go
  }) {
    const p = POSTS[0];
    return /*#__PURE__*/React.createElement("main", {
      className: "ps-container",
      style: {
        paddingTop: "var(--space-8)",
        paddingBottom: "var(--space-8)"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        maxWidth: "var(--container-article)",
        margin: "0 auto"
      }
    }, /*#__PURE__*/React.createElement("a", {
      href: "#",
      onClick: e => {
        e.preventDefault();
        go("home");
      },
      style: {
        display: "inline-flex",
        alignItems: "center",
        gap: "6px",
        fontSize: "13px",
        fontWeight: 600,
        marginBottom: "16px",
        color: "var(--color-text-muted)",
        fontFamily: "var(--font-mono)",
        textTransform: "uppercase",
        letterSpacing: "0.06em"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-left",
      size: 16
    }), " Back to latest"), /*#__PURE__*/React.createElement("div", {
      className: "ps-chip-group",
      style: {
        marginBottom: "16px"
      }
    }, p.tags.map((t, i) => /*#__PURE__*/React.createElement(Chip, {
      key: i,
      variant: t.variant,
      href: "#"
    }, t.label))), /*#__PURE__*/React.createElement("h1", {
      style: {
        fontFamily: "var(--font-display)",
        fontSize: "var(--fs-article)",
        lineHeight: "var(--lh-article)",
        fontWeight: 700,
        letterSpacing: "var(--ls-display)",
        textWrap: "balance"
      }
    }, p.title), /*#__PURE__*/React.createElement("p", {
      style: {
        color: "var(--color-text-muted)",
        fontSize: "14px",
        marginTop: "14px"
      }
    }, "By ", p.byline, " \xB7 ", p.date), /*#__PURE__*/React.createElement("div", {
      className: "ps-article-hero",
      style: {
        margin: "24px 0"
      }
    }, /*#__PURE__*/React.createElement("img", {
      src: keyart(p.art?.[0] || "#1B1320", p.art?.[1] || "#2A1320", 1040, 520),
      alt: ""
    })), /*#__PURE__*/React.createElement(ArticleBody, null, /*#__PURE__*/React.createElement("p", null, p.excerpt, " The announcement arrived at the very end of a 40-minute broadcast, after a run of release-date confirmations that had already lifted expectations across the import community."), /*#__PURE__*/React.createElement("h2", null, "What was actually confirmed"), /*#__PURE__*/React.createElement("p", null, "The publisher committed to a simultaneous PC and console launch, with a release window rather than a fixed date. A storefront page went live minutes later, listing supported languages and a pre-order incentive."), /*#__PURE__*/React.createElement("ul", null, /*#__PURE__*/React.createElement("li", null, "Worldwide PC release alongside consoles"), /*#__PURE__*/React.createElement("li", null, "Full English, Japanese, and Korean text"), /*#__PURE__*/React.createElement("li", null, "Pre-order bonus cosmetic set")), /*#__PURE__*/React.createElement("blockquote", null, "The import scene has kept this series visible for years. A global PC window is the payoff.", /*#__PURE__*/React.createElement("cite", null, "\u2014 Pixel Sakura desk")), /*#__PURE__*/React.createElement("h3", null, "Why it matters"), /*#__PURE__*/React.createElement("p", null, "For PC players who have relied on imports and fan patches, a same-day global release closes a gap that has defined the franchise's reputation abroad. Read more on our ", /*#__PURE__*/React.createElement("a", {
      href: "#"
    }, "JRPG topic page"), "."))));
  }
  function NotFound({
    go
  }) {
    return /*#__PURE__*/React.createElement("main", {
      className: "ps-container",
      style: {
        padding: "96px 32px"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        maxWidth: "460px",
        margin: "0 auto",
        textAlign: "center"
      }
    }, /*#__PURE__*/React.createElement("div", {
      "aria-hidden": "true",
      style: {
        display: "flex",
        justifyContent: "center",
        marginBottom: "20px"
      },
      dangerouslySetInnerHTML: {
        __html: MARK.replace(/28/g, '56')
      }
    }), /*#__PURE__*/React.createElement("h1", {
      className: "ps-display",
      style: {
        fontFamily: "var(--font-display)",
        fontSize: "var(--fs-article)",
        fontWeight: 700,
        letterSpacing: "var(--ls-display)"
      }
    }, "Page not found"), /*#__PURE__*/React.createElement("p", {
      style: {
        color: "var(--color-text-muted)",
        margin: "12px 0 24px"
      }
    }, "The story you're looking for may have moved or never existed. The latest news is always one click away."), /*#__PURE__*/React.createElement(Button, {
      variant: "primary",
      onClick: () => go("home")
    }, "Back to latest stories")));
  }

  // ---------- App shell ----------
  function App() {
    const [view, setView] = React.useState("home");
    const go = v => {
      setView(v);
      window.scrollTo(0, 0);
    };
    const taxNames = {
      news: ["News", "The latest game news from across the Asian PC and console scene."],
      review: ["Reviews", "Hands-on verdicts on imports and global releases."],
      pc: ["PC", "Coverage for the PC platform."],
      switch: ["Nintendo Switch", "Coverage for the Switch platform."],
      ps5: ["PlayStation 5", "Coverage for the PS5 platform."]
    };
    let screen;
    if (view === "home") screen = /*#__PURE__*/React.createElement(HomeFeed, {
      go: go,
      empty: false
    });else if (view === "empty") screen = /*#__PURE__*/React.createElement(HomeFeed, {
      go: go,
      empty: true
    });else if (view === "article") screen = /*#__PURE__*/React.createElement(Article, {
      go: go
    });else if (view === "404") screen = /*#__PURE__*/React.createElement(NotFound, {
      go: go
    });else screen = /*#__PURE__*/React.createElement(Taxonomy, {
      go: go,
      name: taxNames[view]?.[0] || "Topic",
      desc: taxNames[view]?.[1]
    });
    return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(Header, {
      go: go,
      current: view
    }), screen, /*#__PURE__*/React.createElement(Footer, null), /*#__PURE__*/React.createElement(Switcher, {
      view: view,
      setView: go
    }));
  }

  // Floating view switcher (kit only — not part of the product)
  function Switcher({
    view,
    setView
  }) {
    const items = [["home", "Home"], ["news", "Taxonomy"], ["article", "Article"], ["empty", "Empty"], ["404", "404"]];
    return /*#__PURE__*/React.createElement("div", {
      style: {
        position: "fixed",
        bottom: "16px",
        left: "50%",
        transform: "translateX(-50%)",
        display: "flex",
        gap: "4px",
        background: "var(--color-surface)",
        border: "1px solid var(--color-border)",
        borderRadius: "999px",
        padding: "4px",
        boxShadow: "var(--shadow-md)",
        zIndex: 100
      }
    }, items.map(([k, l]) => /*#__PURE__*/React.createElement("button", {
      key: k,
      onClick: () => setView(k),
      style: {
        border: 0,
        cursor: "pointer",
        borderRadius: "999px",
        padding: "6px 14px",
        fontSize: "13px",
        fontWeight: 600,
        background: view === k ? "var(--color-primary)" : "transparent",
        color: view === k ? "#fff" : "var(--color-text-muted)"
      }
    }, l)));
  }
  window.PSPublicKit = {
    App
  };
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/public-site/kit.jsx", error: String((e && e.message) || e) }); }

__ds_ns.ArticleBody = __ds_scope.ArticleBody;

__ds_ns.SakuraMark = __ds_scope.SakuraMark;

__ds_ns.Brand = __ds_scope.Brand;

__ds_ns.Pagination = __ds_scope.Pagination;

__ds_ns.PostCard = __ds_scope.PostCard;

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Chip = __ds_scope.Chip;

__ds_ns.Icon = __ds_scope.Icon;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.DataTable = __ds_scope.DataTable;

__ds_ns.Notice = __ds_scope.Notice;

__ds_ns.CheckboxGroup = __ds_scope.CheckboxGroup;

__ds_ns.Field = __ds_scope.Field;

__ds_ns.Select = __ds_scope.Select;

__ds_ns.TextInput = __ds_scope.TextInput;

__ds_ns.Textarea = __ds_scope.Textarea;

})();
