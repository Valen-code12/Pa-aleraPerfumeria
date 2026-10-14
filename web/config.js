const API_URL = "panalera-production.up.railway.app";
const _fetch = window.fetch;
window.fetch = (url, opts) =>
  _fetch(typeof url === "string" && url.startsWith("/") ? API_URL + url : url, opts);