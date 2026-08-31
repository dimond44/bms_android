(function () {
  "use strict";

  const DEFAULT_API_KEY = "change_me_api_key_2026";
  const API_KEY_STORAGE = "liferych:bms:api-key";
  const THEME_STORAGE = "liferych:bms:theme";
  const REFRESH_INTERVAL_MS = 15000;
  const ONLINE_AFTER_MS = 45000;
  const HISTORY_LIMIT = 100;

  const state = {
    apiKey: localStorage.getItem(API_KEY_STORAGE) || "",
    batteries: [],
    history: [],
    selectedUid: localStorage.getItem("liferych:bms:selected") || "",
    search: "",
    refreshTimer: 0,
    abortController: null,
    requestId: 0,
  };

  const dom = {
    root: document.documentElement,
    serverState: document.getElementById("serverState"),
    updatedAt: document.getElementById("updatedAt"),
    refreshButton: document.getElementById("refreshButton"),
    themeButton: document.getElementById("themeButton"),
    batteryCount: document.getElementById("batteryCount"),
    searchInput: document.getElementById("searchInput"),
    batteryList: document.getElementById("batteryList"),
    emptyState: document.getElementById("emptyState"),
    dashboard: document.getElementById("dashboard"),
    batteryName: document.getElementById("batteryName"),
    onlineStatus: document.getElementById("onlineStatus"),
    batteryUid: document.getElementById("batteryUid"),
    batteryAddress: document.getElementById("batteryAddress"),
    lastSeen: document.getElementById("lastSeen"),
    configCheck: document.getElementById("configCheck"),
    configCheckStatus: document.getElementById("configCheckStatus"),
    configCheckMeta: document.getElementById("configCheckMeta"),
    configCheckDetails: document.getElementById("configCheckDetails"),
    socGauge: document.getElementById("socGauge"),
    socValue: document.getElementById("socValue"),
    remainingValue: document.getElementById("remainingValue"),
    metricGrid: document.getElementById("metricGrid"),
    chargeMos: document.getElementById("chargeMos"),
    dischargeMos: document.getElementById("dischargeMos"),
    tempRange: document.getElementById("tempRange"),
    temperatureList: document.getElementById("temperatureList"),
    cellRange: document.getElementById("cellRange"),
    cellList: document.getElementById("cellList"),
    historyChart: document.getElementById("historyChart"),
    chartEmpty: document.getElementById("chartEmpty"),
    errorCount: document.getElementById("errorCount"),
    errorList: document.getElementById("errorList"),
    eventList: document.getElementById("eventList"),
    historyCount: document.getElementById("historyCount"),
    historyBody: document.getElementById("historyBody"),
    toast: document.getElementById("toast"),
    keyDialog: document.getElementById("keyDialog"),
    keyForm: document.getElementById("keyForm"),
    keyInput: document.getElementById("keyInput"),
    keyError: document.getElementById("keyError"),
  };

  init();

  function init() {
    applyTheme(localStorage.getItem(THEME_STORAGE) || "light");
    dom.refreshButton.addEventListener("click", () => refresh({ manual: true }));
    dom.themeButton.addEventListener("click", toggleTheme);
    dom.searchInput.addEventListener("input", () => {
      state.search = dom.searchInput.value.trim().toLowerCase();
      renderBatteryList();
    });
    dom.keyForm.addEventListener("submit", handleKeySubmit);
    window.addEventListener("resize", debounce(() => drawHistoryChart(), 120));

    if (!state.apiKey) {
      dom.keyInput.value = DEFAULT_API_KEY;
      openKeyDialog(false);
    } else {
      refresh();
    }

    state.refreshTimer = window.setInterval(() => refresh(), REFRESH_INTERVAL_MS);
  }

  async function handleKeySubmit(event) {
    event.preventDefault();
    const value = dom.keyInput.value.trim();
    if (!value) return;

    state.apiKey = value;
    localStorage.setItem(API_KEY_STORAGE, value);
    dom.keyInput.value = "";
    dom.keyError.classList.add("hidden");
    closeKeyDialog();
    await refresh({ manual: true });
  }

  async function refresh(options) {
    if (!state.apiKey) {
      openKeyDialog(false);
      return;
    }

    const requestId = ++state.requestId;
    if (state.abortController) state.abortController.abort();
    state.abortController = new AbortController();

    setLoading(true);
    setServerState("loading", "Обновление…");

    try {
      const batteriesBody = await apiGet("/api/v1/batteries", state.abortController.signal);
      if (requestId !== state.requestId) return;

      state.batteries = Array.isArray(batteriesBody.batteries) ? batteriesBody.batteries : [];
      reconcileSelection();
      renderBatteryList();

      if (state.selectedUid) {
        const historyPath = `/api/v1/batteries/${encodeURIComponent(state.selectedUid)}/telemetry?limit=${HISTORY_LIMIT}`;
        const historyBody = await apiGet(historyPath, state.abortController.signal);
        if (requestId !== state.requestId) return;
        state.history = Array.isArray(historyBody.telemetry) ? historyBody.telemetry : [];
      } else {
        state.history = [];
      }

      renderDashboard();
      setServerState("online", "Сервер онлайн");
      dom.updatedAt.textContent = `Обновлено ${formatTime(Date.now())}`;
      if (options && options.manual) showToast("Данные обновлены");
    } catch (error) {
      if (error.name === "AbortError") return;
      if (error.status === 401) {
        state.apiKey = "";
        localStorage.removeItem(API_KEY_STORAGE);
        openKeyDialog(true);
        setServerState("error", "Нужен ключ");
        return;
      }
      setServerState("error", "Ошибка связи");
      renderListMessage("Не удалось загрузить данные. Проверьте сервер и попробуйте снова.");
      showToast("Не удалось загрузить данные");
    } finally {
      if (requestId === state.requestId) setLoading(false);
    }
  }

  async function apiGet(path, signal) {
    const response = await fetch(path, {
      headers: { "x-api-key": state.apiKey },
      signal,
    });

    if (response.status === 401) {
      const error = new Error("unauthorized");
      error.status = 401;
      throw error;
    }
    if (!response.ok) {
      const error = new Error(`request failed: ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  function reconcileSelection() {
    const selectedExists = state.batteries.some((battery) => battery.bms_uid === state.selectedUid);
    if (!selectedExists) {
      state.selectedUid = state.batteries[0] ? state.batteries[0].bms_uid : "";
    }
    if (state.selectedUid) {
      localStorage.setItem("liferych:bms:selected", state.selectedUid);
    } else {
      localStorage.removeItem("liferych:bms:selected");
    }
  }

  function renderBatteryList() {
    dom.batteryCount.textContent = String(state.batteries.length);
    clear(dom.batteryList);

    const batteries = state.batteries.filter((battery) => {
      const haystack = [
        battery.bluetooth_name,
        battery.bms_uid,
        battery.bluetooth_address,
      ].filter(Boolean).join(" ").toLowerCase();
      return haystack.includes(state.search);
    });

    if (batteries.length === 0) {
      renderListMessage(state.batteries.length === 0 ? "Пока нет данных от аккумуляторов." : "Ничего не найдено.");
      return;
    }

    for (const battery of batteries) {
      const button = el("button", "battery-item");
      button.type = "button";
      if (battery.bms_uid === state.selectedUid) button.classList.add("active");
      button.addEventListener("click", () => selectBattery(battery.bms_uid));

      const main = el("span", "battery-main");
      appendText(main, "span", "battery-name", displayName(battery));
      appendText(main, "span", "battery-uid", battery.bms_uid || "Без UID");
      const configBadge = appendText(main, "span", "config-badge", configBadgeText(battery.config_check));
      configBadge.classList.add(configStatusClass(battery.config_check));

      const side = el("span", "battery-side");
      appendText(side, "span", "battery-soc", formatPercent(battery.soc));
      const status = appendText(side, "span", "battery-status", isOnline(battery.last_seen_at) ? "online" : "offline");
      if (isOnline(battery.last_seen_at)) status.classList.add("online");
      const errors = Array.isArray(battery.errors) ? battery.errors.length : 0;
      if (errors > 0) appendText(side, "span", "battery-errors", `${errors} ош.`);

      button.append(main, side);
      dom.batteryList.append(button);
    }
  }

  async function selectBattery(uid) {
    if (uid === state.selectedUid) return;
    state.selectedUid = uid;
    localStorage.setItem("liferych:bms:selected", uid);
    state.history = [];
    renderBatteryList();
    renderDashboard();
    await refresh({ manual: false });
  }

  function renderDashboard() {
    const battery = getSelectedBattery();
    if (!battery) {
      dom.emptyState.classList.remove("hidden");
      dom.dashboard.classList.add("hidden");
      drawHistoryChart();
      return;
    }

    const latest = state.history[0] || battery;
    dom.emptyState.classList.add("hidden");
    dom.dashboard.classList.remove("hidden");

    dom.batteryName.textContent = displayName(battery);
    dom.batteryUid.textContent = battery.bms_uid || "—";
    dom.batteryAddress.textContent = battery.bluetooth_address || "адрес не указан";
    dom.lastSeen.textContent = `Последняя связь: ${formatDateTime(battery.last_seen_at)}`;
    setOnlinePill(battery);
    renderConfigCheck(battery.config_check);

    const soc = numberOrNull(latest.soc);
    dom.socGauge.style.setProperty("--soc", String(clamp(soc || 0, 0, 100)));
    dom.socValue.textContent = soc == null ? "—" : String(Math.round(soc));
    dom.remainingValue.textContent = formatAmpHours(latest.remaining_ah);

    renderMetrics(latest);
    renderMos(dom.chargeMos, latest.charge_mos);
    renderMos(dom.dischargeMos, latest.discharge_mos);
    renderCells(latest);
    renderTemperatures(latest);
    renderFeeds(latest);
    renderHistoryTable();
    drawHistoryChart();
  }

  function renderConfigCheck(check) {
    clear(dom.configCheckMeta);
    clear(dom.configCheckDetails);
    dom.configCheck.classList.remove("ok", "mismatch", "incomplete", "unchecked");
    const statusClass = configStatusClass(check);
    dom.configCheck.classList.add(statusClass);
    dom.configCheckStatus.className = `config-check-status ${statusClass}`;
    dom.configCheckStatus.textContent = configBadgeText(check);

    if (!check) {
      appendText(dom.configCheckMeta, "span", "", "Результат проверки ещё не загружен.");
      appendText(dom.configCheckDetails, "div", "config-empty", "Запустите проверку конфигурации в Android-приложении.");
      return;
    }

    appendText(dom.configCheckMeta, "span", "", `Проверено: ${formatDateTime(check.checked_at)}`);
    appendText(dom.configCheckMeta, "span", "", `Шаблон: ${check.template_id || "—"} v${check.template_version ?? "—"}`);
    appendText(dom.configCheckMeta, "span", "", check.series_count == null ? "Серия: —" : `${check.series_count}S`);

    const mismatches = Array.isArray(check.mismatches) ? check.mismatches : [];
    const missing = Array.isArray(check.missing) ? check.missing : [];

    renderConfigGroup(
      "Отклонения",
      mismatches,
      "mismatch",
      (item) => `Ожидалось: ${formatConfigValue(item.expected, item.unit)} · Фактически: ${formatConfigValue(item.actual, item.unit)}`,
    );
    renderConfigGroup(
      "Нет данных",
      missing,
      "missing",
      (item) => `Ожидалось: ${formatConfigValue(item.expected, item.unit)} · Нет данных${item.reason ? ` · ${item.reason}` : ""}`,
    );

    if (mismatches.length + missing.length === 0) {
      appendText(dom.configCheckDetails, "div", "config-empty", "Отклонений не обнаружено.");
    }
  }

  function renderConfigGroup(title, items, kind, describe) {
    if (items.length === 0) return;
    const group = el("section", `config-group ${kind}`);
    appendText(group, "h4", "", `${title} · ${items.length}`);
    for (const item of items) {
      const row = el("div", "config-item");
      appendText(row, "strong", "", item.label || item.key || "Параметр");
      appendText(row, "span", "", describe(item));
      group.append(row);
    }
    dom.configCheckDetails.append(group);
  }

  function configBadgeText(check) {
    if (!check) return "Не проверялась";
    if (check.status === "ok") return "Конфигурация OK";
    if (check.status === "mismatch") return `${check.mismatch_count || 0} отклонений`;
    return "Проверка неполная";
  }

  function configStatusClass(check) {
    if (!check) return "unchecked";
    return ["ok", "mismatch", "incomplete"].includes(check.status) ? check.status : "unchecked";
  }

  function renderMetrics(latest) {
    clear(dom.metricGrid);
    const metrics = [
      ["Напряжение", formatVoltage(latest.voltage), "Суммарное"],
      ["Ток", formatCurrent(latest.current), currentNote(latest.current)],
      ["Ёмкость", formatCapacity(latest), "Оценка батареи"],
      ["Delta", formatVoltage(latest.cell_diff_v), "Разброс ячеек"],
      ["Мин. ячейка", formatVoltage(latest.min_cell_v), "Минимум"],
      ["Макс. ячейка", formatVoltage(latest.max_cell_v), "Максимум"],
    ];

    for (const [label, value, note] of metrics) {
      const card = el("article", "metric");
      appendText(card, "span", "metric-label", label);
      appendText(card, "strong", "metric-value", value);
      appendText(card, "span", "metric-note", note);
      dom.metricGrid.append(card);
    }
  }

  function renderMos(node, value) {
    node.classList.toggle("on", value === true);
    const text = value == null ? "—" : value ? "вкл" : "выкл";
    node.querySelector("strong").textContent = text;
  }

  function renderCells(latest) {
    clear(dom.cellList);
    const entries = sortedEntries(latest.cells);
    if (entries.length === 0) {
      dom.cellRange.textContent = "нет данных";
      appendText(dom.cellList, "div", "feed-empty", "Нет данных по ячейкам.");
      return;
    }

    const values = entries.map((entry) => entry[1]);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = Math.max(max - min, 0.001);
    dom.cellRange.textContent = `${formatVoltage(min)} — ${formatVoltage(max)}`;

    for (const [label, value] of entries) {
      const cell = el("div", "cell");
      if (value === min) cell.classList.add("extreme-min");
      if (value === max) cell.classList.add("extreme-max");

      const head = el("div", "cell-head");
      appendText(head, "span", "", `Ячейка ${label}`);
      appendText(head, "strong", "", formatVoltage(value));

      const track = el("div", "cell-track");
      const fill = el("div", "cell-fill");
      fill.style.width = `${Math.round(((value - min) / span) * 72 + 28)}%`;
      track.append(fill);
      cell.append(head, track);
      dom.cellList.append(cell);
    }
  }

  function renderTemperatures(latest) {
    clear(dom.temperatureList);
    const entries = sortedEntries(latest.temps);
    const minTemp = numberOrNull(latest.min_temp);
    const maxTemp = numberOrNull(latest.max_temp);
    dom.tempRange.textContent = minTemp == null || maxTemp == null ? "нет диапазона" : `${minTemp}…${maxTemp} °C`;

    if (entries.length === 0) {
      appendText(dom.temperatureList, "div", "feed-empty", "Нет данных температур.");
      return;
    }

    for (const [label, value] of entries) {
      const item = el("div", "temperature");
      appendText(item, "span", "", `Датчик ${label}`);
      appendText(item, "strong", "", `${formatNumber(value, 0)} °C`);
      dom.temperatureList.append(item);
    }
  }

  function renderFeeds(latest) {
    const errors = Array.isArray(latest.errors) ? latest.errors : [];
    const events = Array.isArray(latest.events) ? latest.events : [];
    dom.errorCount.textContent = String(errors.length);
    renderFeed(dom.errorList, errors, "error", "Ошибок нет.");
    renderFeed(dom.eventList, events, "event", "Событий пока нет.");
  }

  function renderFeed(container, items, type, emptyText) {
    clear(container);
    if (items.length === 0) {
      appendText(container, "div", "feed-empty", emptyText);
      return;
    }

    for (const item of items.slice(0, 12)) {
      const row = el("div", `feed-item ${type}`);
      if (typeof item === "string") {
        appendText(row, "strong", "", item);
      } else {
        appendText(row, "strong", "", item.text || item.type || "Событие");
        appendText(row, "span", "", [item.severity, item.local_time].filter(Boolean).join(" • ") || "—");
      }
      container.append(row);
    }
  }

  function renderHistoryTable() {
    clear(dom.historyBody);
    dom.historyCount.textContent = pluralRecords(state.history.length);

    if (state.history.length === 0) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 7;
      cell.textContent = "История пока пуста.";
      row.append(cell);
      dom.historyBody.append(row);
      return;
    }

    for (const item of state.history.slice(0, 25)) {
      const row = document.createElement("tr");
      appendText(row, "td", "", formatDateTime(item.received_at));
      appendText(row, "td", "", formatPercent(item.soc));
      appendText(row, "td", "", formatVoltage(item.voltage));
      appendText(row, "td", "", formatCurrent(item.current));
      appendText(row, "td", "", formatVoltage(item.cell_diff_v));
      appendText(row, "td", "", formatTempRange(item));
      const errors = Array.isArray(item.errors) ? item.errors.length : 0;
      const errorCell = appendText(row, "td", errors > 0 ? "error-text" : "", errors > 0 ? String(errors) : "нет");
      if (errors > 0) errorCell.title = item.errors.join(", ");
      dom.historyBody.append(row);
    }
  }

  function drawHistoryChart() {
    const canvas = dom.historyChart;
    if (!canvas) return;

    const points = state.history.slice().reverse().filter((item) => {
      return numberOrNull(item.soc) != null || numberOrNull(item.voltage) != null;
    });
    dom.chartEmpty.classList.toggle("hidden", points.length >= 2);

    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.floor(rect.width * dpr));
    const height = Math.max(1, Math.floor(rect.height * dpr));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }

    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, width, height);
    if (points.length < 2) return;

    ctx.save();
    ctx.scale(dpr, dpr);

    const css = getComputedStyle(dom.root);
    const grid = css.getPropertyValue("--line").trim();
    const text = css.getPropertyValue("--muted").trim();
    const yellow = css.getPropertyValue("--yellow-deep").trim();
    const blue = css.getPropertyValue("--blue").trim();
    const chartWidth = rect.width;
    const chartHeight = rect.height;
    const padding = { top: 18, right: 16, bottom: 28, left: 42 };
    const plotWidth = chartWidth - padding.left - padding.right;
    const plotHeight = chartHeight - padding.top - padding.bottom;

    ctx.strokeStyle = grid;
    ctx.lineWidth = 1;
    ctx.font = "11px Segoe UI, sans-serif";
    ctx.fillStyle = text;
    for (let i = 0; i <= 4; i += 1) {
      const y = padding.top + (plotHeight / 4) * i;
      ctx.beginPath();
      ctx.moveTo(padding.left, y);
      ctx.lineTo(chartWidth - padding.right, y);
      ctx.stroke();
    }

    drawSeries(ctx, points, "soc", yellow, 0, 100, padding, plotWidth, plotHeight);
    const voltages = points.map((item) => numberOrNull(item.voltage)).filter((value) => value != null);
    if (voltages.length >= 2) {
      const minVoltage = Math.min(...voltages);
      const maxVoltage = Math.max(...voltages);
      const voltagePadding = Math.max((maxVoltage - minVoltage) * 0.15, 0.2);
      drawSeries(ctx, points, "voltage", blue, minVoltage - voltagePadding, maxVoltage + voltagePadding, padding, plotWidth, plotHeight);
    }

    ctx.fillStyle = text;
    ctx.fillText("100%", 6, padding.top + 4);
    ctx.fillText("0%", 18, padding.top + plotHeight + 4);
    ctx.restore();
  }

  function drawSeries(ctx, points, field, color, min, max, padding, plotWidth, plotHeight) {
    const values = points.map((item) => numberOrNull(item[field]));
    if (values.filter((value) => value != null).length < 2) return;

    ctx.strokeStyle = color;
    ctx.lineWidth = 2.5;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.beginPath();

    let started = false;
    values.forEach((value, index) => {
      if (value == null) return;
      const x = padding.left + (plotWidth * index) / Math.max(points.length - 1, 1);
      const y = padding.top + plotHeight - ((value - min) / Math.max(max - min, 1)) * plotHeight;
      if (!started) {
        ctx.moveTo(x, y);
        started = true;
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.stroke();
  }

  function setOnlinePill(battery) {
    const online = isOnline(battery.last_seen_at);
    dom.onlineStatus.textContent = online ? "online" : "offline";
    dom.onlineStatus.classList.toggle("online", online);
  }

  function openKeyDialog(hasError) {
    dom.keyError.classList.toggle("hidden", !hasError);
    if (!dom.keyInput.value) dom.keyInput.value = DEFAULT_API_KEY;
    if (!dom.keyDialog.open) dom.keyDialog.showModal();
    dom.keyInput.focus();
  }

  function closeKeyDialog() {
    if (dom.keyDialog.open) dom.keyDialog.close();
  }

  function setLoading(isLoading) {
    dom.refreshButton.disabled = isLoading;
    dom.refreshButton.classList.toggle("loading", isLoading);
  }

  function setServerState(kind, text) {
    dom.serverState.classList.toggle("online", kind === "online");
    dom.serverState.classList.toggle("error", kind === "error");
    dom.serverState.querySelector("span").textContent = text;
  }

  function renderListMessage(text) {
    clear(dom.batteryList);
    appendText(dom.batteryList, "div", "list-message", text);
  }

  function showToast(text) {
    dom.toast.textContent = text;
    dom.toast.classList.remove("hidden");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => dom.toast.classList.add("hidden"), 2600);
  }

  function toggleTheme() {
    applyTheme(dom.root.dataset.theme === "dark" ? "light" : "dark");
  }

  function applyTheme(theme) {
    dom.root.dataset.theme = theme;
    dom.themeButton.textContent = theme === "dark" ? "☀" : "☾";
    localStorage.setItem(THEME_STORAGE, theme);
    window.requestAnimationFrame(() => drawHistoryChart());
  }

  function getSelectedBattery() {
    return state.batteries.find((battery) => battery.bms_uid === state.selectedUid) || null;
  }

  function displayName(battery) {
    return battery.bluetooth_name || battery.bms_uid || "Аккумулятор";
  }

  function isOnline(timestamp) {
    const value = numberOrNull(timestamp);
    return value != null && Date.now() - value <= ONLINE_AFTER_MS;
  }

  function sortedEntries(object) {
    if (!object || typeof object !== "object" || Array.isArray(object)) return [];
    return Object.entries(object)
      .map(([key, value]) => [key, numberOrNull(value)])
      .filter((entry) => entry[1] != null)
      .sort((left, right) => Number(left[0]) - Number(right[0]));
  }

  function appendText(parent, tag, className, text) {
    const node = el(tag, className);
    node.textContent = text;
    parent.append(node);
    return node;
  }

  function el(tag, className) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
  }

  function clear(node) {
    node.replaceChildren();
  }

  function numberOrNull(value) {
    return typeof value === "number" && Number.isFinite(value) ? value : null;
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function formatNumber(value, digits) {
    const number = numberOrNull(value);
    if (number == null) return "—";
    return new Intl.NumberFormat("ru-RU", {
      maximumFractionDigits: digits,
      minimumFractionDigits: digits,
    }).format(number);
  }

  function formatConfigValue(value, unit) {
    let text;
    if (value == null) {
      text = "—";
    } else if (typeof value === "number" && Number.isFinite(value)) {
      text = formatNumber(value, value % 1 === 0 ? 0 : 3);
    } else if (typeof value === "boolean") {
      text = value ? "да" : "нет";
    } else if (typeof value === "string") {
      text = value;
    } else {
      try {
        text = JSON.stringify(value);
      } catch {
        text = String(value);
      }
    }
    return unit ? `${text} ${unit}` : text;
  }

  function formatPercent(value) {
    const number = numberOrNull(value);
    return number == null ? "—" : `${formatNumber(number, number % 1 === 0 ? 0 : 1)}%`;
  }

  function formatVoltage(value) {
    const number = numberOrNull(value);
    return number == null ? "—" : `${formatNumber(number, 3)} В`;
  }

  function formatCurrent(value) {
    const number = numberOrNull(value);
    return number == null ? "—" : `${formatNumber(number, 1)} А`;
  }

  function formatAmpHours(value) {
    const number = numberOrNull(value);
    return number == null ? "—" : `${formatNumber(number, 1)} А·ч`;
  }

  function formatCapacity(latest) {
    const estimated = numberOrNull(latest.estimated_full_ah);
    const nominal = numberOrNull(latest.nominal_capacity_ah);
    if (estimated == null && nominal == null) return "—";
    if (estimated != null && nominal != null) return `${formatNumber(estimated, 1)} / ${formatNumber(nominal, 1)} А·ч`;
    return formatAmpHours(estimated ?? nominal);
  }

  function currentNote(value) {
    const number = numberOrNull(value);
    if (number == null || number === 0) return "Покой";
    return number > 0 ? "Заряд" : "Разряд";
  }

  function formatTempRange(item) {
    const min = numberOrNull(item.min_temp);
    const max = numberOrNull(item.max_temp);
    if (min == null || max == null) return "—";
    return `${formatNumber(min, 0)}…${formatNumber(max, 0)} °C`;
  }

  function formatTime(timestamp) {
    return new Intl.DateTimeFormat("ru-RU", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).format(new Date(timestamp));
  }

  function formatDateTime(timestamp) {
    const value = numberOrNull(timestamp);
    if (value == null) return "—";
    return new Intl.DateTimeFormat("ru-RU", {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).format(new Date(value));
  }

  function pluralRecords(count) {
    const mod10 = count % 10;
    const mod100 = count % 100;
    if (mod10 === 1 && mod100 !== 11) return `${count} запись`;
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${count} записи`;
    return `${count} записей`;
  }

  function debounce(fn, wait) {
    let timer = 0;
    return function debounced() {
      window.clearTimeout(timer);
      timer = window.setTimeout(fn, wait);
    };
  }
})();
