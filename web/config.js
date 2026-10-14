const API_URL = "https://pa-aleraperfumeria.onrender.com"; // sin "/" al final
const _fetch = window.fetch;
window.fetch = (url, opts) =>
  _fetch(typeof url === "string" && url.startsWith("/") ? API_URL + url : url, opts);