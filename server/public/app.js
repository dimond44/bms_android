(function () {
  "use strict";

  const DEFAULT_API_KEY = "change_me_api_key_2026";
  const API_KEY_STORAGE = "liferych:bms:api-key";
  const THEME_STORAGE = "liferych:bms:theme";
  const TAB_STORAGE = "liferych:bms:tab";
  const DASH_TABS = ["overview", "owner", "balancing", "dynamics", "journal", "archive", "write", "service"];
  const REFRESH_INTERVAL_MS = 15000;
  const ONLINE_AFTER_MS = 45000;
  const HISTORY_LIMIT = 100;
  const DL_RED_SKIPPED_KEYS = new Set([
    "soc_calibration_0",
    "soc_calibration_100",
    "balance_start_voltage",
    "balance_stop_voltage",
    "balance_delta",
  ]);
  const RED_DL_LABEL = "Красная BMS DL-серия (старая)";

  function cleanUid(value) {
    if (typeof value !== "string") return "";
    return value.split("\0")[0].replace(/[\u0000-\u001F\u007F]/g, "").trim();
  }

  const state = {
    apiKey: localStorage.getItem(API_KEY_STORAGE) || "",
    batteries: [],
    history: [],
    selectedUid: cleanUid(localStorage.getItem("liferych:bms:selected") || ""),
    search: "",
    dashboardTab: localStorage.getItem(TAB_STORAGE) || "overview",
    refreshTimer: 0,
    abortController: null,
    requestId: 0,
    configTemplate: null,
    templatePromise: null,
    writeDrafts: {},
    writeSourceName: "",
    writeRenderedUid: "",
    writeCommands: [],
    writeBusyKey: "",
    serviceReports: [],
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
    hardwareBadge: document.getElementById("hardwareBadge"),
    onlineStatus: document.getElementById("onlineStatus"),
    batteryUid: document.getElementById("batteryUid"),
    batterySn: document.getElementById("batterySn"),
    batteryAddress: document.getElementById("batteryAddress"),
    lastSeen: document.getElementById("lastSeen"),
    dashTabs: document.getElementById("dashTabs"),
    ownerCard: document.getElementById("ownerCard"),
    ownerName: document.getElementById("ownerName"),
    ownerPhone: document.getElementById("ownerPhone"),
    ownerEmail: document.getElementById("ownerEmail"),
    ownerEmpty: document.getElementById("ownerEmpty"),
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
    cellSummary: document.getElementById("cellSummary"),
    cellList: document.getElementById("cellList"),
    historyChart: document.getElementById("historyChart"),
    chartEmpty: document.getElementById("chartEmpty"),
    errorCount: document.getElementById("errorCount"),
    errorList: document.getElementById("errorList"),
    eventList: document.getElementById("eventList"),
    historyCount: document.getElementById("historyCount"),
    historyBody: document.getElementById("historyBody"),
    writeTemplateMeta: document.getElementById("writeTemplateMeta"),
    writeFileInput: document.getElementById("writeFileInput"),
    writeLoadTemplate: document.getElementById("writeLoadTemplate"),
    writeFillTemplate: document.getElementById("writeFillTemplate"),
    writeQueueAll: document.getElementById("writeQueueAll"),
    writeDownloadPlan: document.getElementById("writeDownloadPlan"),
    writeFileName: document.getElementById("writeFileName"),
    writeBody: document.getElementById("writeBody"),
    serviceCount: document.getElementById("serviceCount"),
    serviceLatest: document.getElementById("serviceLatest"),
    serviceBody: document.getElementById("serviceBody"),
    toast: document.getElementById("toast"),
    keyDialog: document.getElementById("keyDialog"),
    keyForm: document.getElementById("keyForm"),
    keyInput: document.getElementById("keyInput"),
    keyError: document.getElementById("keyError"),
  };

  init();

  function init() {
    applyTheme(localStorage.getItem(THEME_STORAGE) || "light");
    setDashboardTab(state.dashboardTab, { persist: false, redraw: false });
    if (dom.dashTabs) {
      dom.dashTabs.addEventListener("click", (event) => {
        const button = event.target.closest("[data-tab]");
        if (!button) return;
        setDashboardTab(button.getAttribute("data-tab"));
      });
    }
    dom.refreshButton.addEventListener("click", () => refresh({ manual: true }));
    dom.themeButton.addEventListener("click", toggleTheme);
    dom.searchInput.addEventListener("input", () => {
      state.search = dom.searchInput.value.trim().toLowerCase();
      renderBatteryList();
    });
    dom.keyForm.addEventListener("submit", handleKeySubmit);
    if (dom.writeLoadTemplate) {
      dom.writeLoadTemplate.addEventListener("click", () => loadConfigTemplate({ force: true, fill: false }));
    }
    if (dom.writeFillTemplate) {
      dom.writeFillTemplate.addEventListener("click", fillWriteValuesFromTemplate);
    }
    if (dom.writeQueueAll) {
      dom.writeQueueAll.addEventListener("click", queueAllRemoteWrites);
    }
    if (dom.writeDownloadPlan) {
      dom.writeDownloadPlan.addEventListener("click", downloadWritePlan);
    }
    if (dom.writeFileInput) {
      dom.writeFileInput.addEventListener("change", handleWriteFileUpload);
    }
    window.addEventListener("resize", debounce(() => drawHistoryChart(), 120));

    if (!state.apiKey) {
      state.apiKey = DEFAULT_API_KEY;
      localStorage.setItem(API_KEY_STORAGE, DEFAULT_API_KEY);
    }
    refresh();

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
      state.apiKey = DEFAULT_API_KEY;
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
        try {
          const historyPath = `/api/v1/batteries/${encodeURIComponent(state.selectedUid)}/telemetry?limit=${HISTORY_LIMIT}`;
          const historyBody = await apiGet(historyPath, state.abortController.signal);
          if (requestId !== state.requestId) return;
          state.history = Array.isArray(historyBody.telemetry) ? historyBody.telemetry : [];
        } catch (error) {
          if (error && error.name === "AbortError" && requestId !== state.requestId) return;
          if (error && error.status !== 401) {
            /* keep previously loaded history */
          }
        }
        try {
          const writesPath = `/api/v1/batteries/${encodeURIComponent(state.selectedUid)}/write-commands?limit=20`;
          const writesBody = await apiGet(writesPath, state.abortController.signal);
          if (requestId !== state.requestId) return;
          state.writeCommands = Array.isArray(writesBody.commands) ? writesBody.commands : [];
        } catch (error) {
          if (error && error.name === "AbortError" && requestId !== state.requestId) return;
          if (!(error && error.name === "AbortError")) state.writeCommands = [];
        }
        try {
          const servicePath = `/api/v1/batteries/${encodeURIComponent(state.selectedUid)}/service-reports?limit=50`;
          const serviceBody = await apiGet(servicePath, state.abortController.signal);
          if (requestId !== state.requestId) return;
          state.serviceReports = Array.isArray(serviceBody.service_reports) ? serviceBody.service_reports : [];
        } catch (error) {
          if (error && error.name === "AbortError" && requestId !== state.requestId) return;
          if (!(error && error.name === "AbortError")) state.serviceReports = [];
        }
      } else {
        state.history = [];
        state.writeCommands = [];
        state.serviceReports = [];
      }

      renderDashboard();
      setServerState("online", "Сервер онлайн");
      dom.updatedAt.textContent = `Обновлено ${formatTime(Date.now())}`;
      if (options && options.manual) showToast("Данные обновлены");
    } catch (error) {
      if (error.name === "AbortError") return;
      if (error.status === 401) {
        if (!(options && options.retriedDefaultKey)) {
          state.apiKey = DEFAULT_API_KEY;
          localStorage.setItem(API_KEY_STORAGE, DEFAULT_API_KEY);
          return refresh({ ...options, retriedDefaultKey: true });
        }
        state.apiKey = "";
        localStorage.removeItem(API_KEY_STORAGE);
        openKeyDialog(true);
        setServerState("error", "Нужен ключ");
        return;
      }
      setServerState("error", "Ошибка связи");
      if (state.batteries.length === 0) {
        renderListMessage("Не удалось загрузить данные. Проверьте сервер и попробуйте снова.");
      }
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

  async function apiSend(method, path, body) {
    const response = await fetch(path, {
      method,
      headers: {
        "x-api-key": state.apiKey,
        "content-type": "application/json",
      },
      body: body == null ? undefined : JSON.stringify(body),
    });
    if (response.status === 401) {
      const error = new Error("unauthorized");
      error.status = 401;
      throw error;
    }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.error || `request failed: ${response.status}`);
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  }

  function reconcileSelection() {
    state.selectedUid = cleanUid(state.selectedUid);
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
        battery.advertised_name,
        battery.bms_uid,
        battery.bluetooth_address,
        battery.owner_name,
        battery.owner_phone,
        battery.owner_email,
        battery.assembler_name,
        battery.bms_sn,
        battery.bluetooth_id,
        battery.last_source === "service" ? "сервис" : "",
        isRedDlSeries(battery) ? RED_DL_LABEL : "",
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
      if (textOrEmpty(battery.bms_sn)) {
        appendText(main, "span", "battery-uid", `SN ${battery.bms_sn.trim()}`);
      }
      if (textOrEmpty(battery.owner_name)) {
        appendText(main, "span", "battery-owner", battery.owner_name.trim());
      }
      if (isRedDlSeries(battery)) {
        appendText(main, "span", "family-badge", RED_DL_LABEL);
      }
      if (battery.last_source === "service" || battery.service_report) {
        appendText(main, "span", "service-badge", "сервис");
      }
      const check = visibleConfigCheck(battery);
      const configBadge = appendText(main, "span", "config-badge", configBadgeText(check));
      configBadge.classList.add(configStatusClass(check));

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

    const latest = { ...battery, ...(state.history[0] || {}) };
    dom.emptyState.classList.add("hidden");
    dom.dashboard.classList.remove("hidden");

    dom.batteryName.textContent = displayName(battery);
    renderHardwareBadge(battery);
    dom.batteryUid.textContent = battery.bms_uid || "—";
    if (dom.batterySn) {
      dom.batterySn.textContent = textOrEmpty(battery.bms_sn) ? `SN ${battery.bms_sn.trim()}` : "SN не прочитан";
    }
    dom.batteryAddress.textContent = battery.bluetooth_id || battery.bluetooth_address || "адрес не указан";
    dom.lastSeen.textContent = `Последняя связь: ${formatDateTime(battery.last_seen_at)}`;
    renderOwner(battery);
    setOnlinePill(battery);
    renderConfigCheck(battery);

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
    renderWritePanel(battery);
    renderServicePanel(battery);
    drawHistoryChart();
  }

  function setDashboardTab(tabId, options) {
    const nextTab = DASH_TABS.includes(tabId) ? tabId : "overview";
    state.dashboardTab = nextTab;
    if (!options || options.persist !== false) {
      localStorage.setItem(TAB_STORAGE, nextTab);
    }

    document.querySelectorAll("[data-tab]").forEach((button) => {
      const selected = button.getAttribute("data-tab") === nextTab;
      button.setAttribute("aria-selected", selected ? "true" : "false");
    });
    document.querySelectorAll("[data-tab-panel]").forEach((panel) => {
      panel.classList.toggle("hidden", panel.getAttribute("data-tab-panel") !== nextTab);
    });

    if ((!options || options.redraw !== false) && nextTab === "dynamics") {
      window.requestAnimationFrame(() => drawHistoryChart());
    }
  }

  function renderConfigCheck(battery) {
    const check = visibleConfigCheck(battery);
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
    clear(dom.cellSummary);
    clear(dom.cellList);
    const entries = sortedEntries(latest.cells);
    if (entries.length === 0) {
      appendText(dom.cellSummary, "div", "feed-empty", "нет данных");
      appendText(dom.cellList, "div", "feed-empty", "Нет данных по ячейкам.");
      return;
    }

    const values = entries.map((entry) => entry[1]);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const spread = numberOrNull(latest.cell_diff_v) ?? (max - min);
    const series = entries.length;
    const seriesLabel = series === 4 || series === 8 ? `${series}S` : `${series} эл.`;
    const evenPack = min === max;

    const packVoltage = numberOrNull(latest.voltage) ?? values.reduce((sum, value) => sum + value, 0);
    const chips = [
      ["Сборка", seriesLabel],
      ["Напряжение АКБ", formatVoltage(packVoltage)],
      ["Разброс Δ", formatMillivolts(spread, false)],
      ["Мин. ячейка", formatVoltage(min)],
      ["Макс. ячейка", formatVoltage(max)],
    ];
    for (const [label, value] of chips) {
      const chip = el("div", "cell-chip");
      appendText(chip, "span", "", label);
      appendText(chip, "strong", "", value);
      dom.cellSummary.append(chip);
    }

    for (const [label, value] of entries) {
      const isMin = !evenPack && value === min;
      const isMax = !evenPack && value === max;
      const cell = el("div", "cell");
      if (isMin) cell.classList.add("extreme-min");
      if (isMax) cell.classList.add("extreme-max");

      const top = el("div", "cell-top");
      appendText(top, "span", "", `Ячейка ${label}`);
      const tag = appendText(
        top,
        "span",
        "cell-tag",
        isMax ? "макс" : isMin ? "мин" : "норма",
      );
      if (isMin) tag.classList.add("min");
      if (isMax) tag.classList.add("max");

      appendText(cell, "strong", "cell-voltage", formatVoltage(value));
      appendText(cell, "span", "cell-delta", `Δ к мин ${formatMillivolts(value - min, true)}`);
      cell.prepend(top);
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

  function serviceTemplateLabel(templateId) {
    if (templateId === "liferych-lfp-12v" || templateId === "12v") return "12В (4S)";
    if (templateId === "liferych-lfp-24v" || templateId === "24v") return "24В (8S)";
    return templateId || "—";
  }

  function serviceStatusLabel(status) {
    if (status === "ok") return "Записано";
    if (status === "partial") return "Частично";
    if (status === "failed") return "Ошибка";
    return status || "—";
  }

  function renderServicePanel() {
    if (!dom.serviceBody || !dom.serviceLatest || !dom.serviceCount) return;
    clear(dom.serviceBody);
    clear(dom.serviceLatest);

    const battery = getSelectedBattery();
    const reports = Array.isArray(state.serviceReports) ? state.serviceReports : [];
    if (battery) {
      const sourceLabel = battery.last_source === "service"
        ? "сервисное приложение"
        : battery.last_source === "user"
          ? "пользовательское приложение"
          : "—";
      appendText(dom.serviceLatest, "span", "", `Источник: ${sourceLabel}`);
      appendText(dom.serviceLatest, "span", "", `Сборщик: ${battery.assembler_name || "—"}`);
      appendText(dom.serviceLatest, "span", "", `SN: ${battery.bms_sn || battery.advertised_name || battery.bluetooth_name || "—"}`);
      appendText(dom.serviceLatest, "span", "", `Bluetooth ID: ${battery.bluetooth_id || battery.bluetooth_address || "—"}`);
    }

    dom.serviceCount.textContent = reports.length === 0
      ? "Нет записей шаблона"
      : `${reports.length} ${reports.length === 1 ? "запись" : "записей"}`;

    if (reports.length === 0) {
      appendText(dom.serviceLatest, "p", "config-empty", "Записей прошивки шаблона ещё нет. Они появятся после кнопки «Записать шаблон» в сервисном приложении. Текущие данные BMS смотрите слева в списке и на вкладке «Основное».");
      const emptyRow = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 7;
      cell.textContent = "История прошивки шаблона пуста.";
      emptyRow.append(cell);
      dom.serviceBody.append(emptyRow);
      return;
    }

    const latest = reports[0];
    appendText(dom.serviceLatest, "span", "", `Последняя запись: ${formatDateTime(latest.written_at)}`);
    appendText(dom.serviceLatest, "span", "", `Шаблон: ${serviceTemplateLabel(latest.template_id)}`);
    appendText(dom.serviceLatest, "span", "", `Ёмкость: ${latest.capacity_ah == null ? "—" : `${latest.capacity_ah} А·ч`}`);
    appendText(dom.serviceLatest, "span", "", `Итог: ${serviceStatusLabel(latest.status)}`);

    for (const report of reports) {
      const row = document.createElement("tr");
      appendText(row, "td", "", formatDateTime(report.written_at));
      appendText(row, "td", "", report.assembler_name || "—");
      appendText(row, "td", "", serviceTemplateLabel(report.template_id));
      appendText(row, "td", "", report.capacity_ah == null ? "—" : `${report.capacity_ah} А·ч`);
      appendText(row, "td", "", report.bms_sn || "—");
      appendText(row, "td", "", report.bluetooth_id || "—");
      const statusCell = appendText(row, "td", "", serviceStatusLabel(report.status));
      if (report.status === "ok") statusCell.classList.add("error-text-ok");
      if (report.status === "failed") statusCell.classList.add("error-text");
      if (Array.isArray(report.items) && report.items.length > 0) {
        statusCell.title = report.items.map((item) => {
          const mark = item.ok ? "ok" : "fail";
          return `${item.label}: ${mark}`;
        }).join("\n");
      }
      dom.serviceBody.append(row);
    }
  }

  const WRITE_SNAPSHOT_ALIASES = {
    sleep_timeout: ["sleep_time_s_num", "sleep_time_s"],
    soc_calibration_0: ["soc_calibration_0_num", "soc_calibration_0_v"],
    soc_calibration_100: ["soc_calibration_100_num", "soc_calibration_100_v"],
  };

  function renderWritePanel(battery) {
    if (!dom.writeBody) return;
    captureWriteDrafts();
    state.writeRenderedUid = battery && battery.bms_uid ? battery.bms_uid : "";
    updateWriteTemplateMeta(battery);
    updateWriteFileName();
    clear(dom.writeBody);

    if (!state.configTemplate) {
      writeEmptyRow("Загрузите шаблон Liferych или JSON-файл конфига.");
      loadConfigTemplate({ force: false });
      return;
    }

    const params = templateParameters();
    if (params.length === 0) {
      writeEmptyRow("В шаблоне нет параметров для записи.");
      return;
    }

    const skipDl = isRedDlSeries(battery);
    const series = detectSeries(battery);
    const bucket = writeDraftBucket(state.writeRenderedUid);

    for (const param of params) {
      const skipped = shouldSkipWriteParam(param, skipDl);
      const row = el("tr", skipped ? "write-row skipped" : "write-row");
      const nameCell = document.createElement("td");
      appendText(nameCell, "div", "write-param-label", param.label || param.key);
      appendText(nameCell, "div", "write-param-key", param.key);
      row.append(nameCell);
      appendText(row, "td", "mono", param.register || "—");
      appendText(row, "td", "", formatExpectedDisplay(param, series));

      const valueCell = document.createElement("td");
      if (skipped) {
        appendText(valueCell, "span", "write-skipped", "не записывается на DL");
      } else {
        const input = document.createElement("input");
        input.type = "text";
        input.inputMode = "decimal";
        input.autocomplete = "off";
        input.spellcheck = false;
        input.setAttribute("data-write-key", param.key);
        input.placeholder = param.unit ? String(param.unit) : "";
        input.value = bucket[param.key] == null ? "" : String(bucket[param.key]);
        input.addEventListener("input", () => {
          writeDraftBucket(state.selectedUid)[param.key] = input.value;
        });
        valueCell.append(input);
      }
      row.append(valueCell);

      const actionCell = document.createElement("td");
      if (!skipped) {
        const wrap = el("div", "write-action");
        const button = document.createElement("button");
        button.type = "button";
        button.className = "write-action-button";
        const command = latestWriteCommand(param.key);
        const busy = command && (command.status === "pending" || command.status === "writing");
        button.textContent = busy ? "В очереди" : "Записать";
        button.disabled = Boolean(busy) || state.writeBusyKey === param.key || state.writeBusyKey === "*";
        button.addEventListener("click", () => queueRemoteWrite(param));
        wrap.append(button);
        if (command) {
          appendText(wrap, "span", `write-action-status ${command.status}`, formatWriteCommandStatus(command));
        }
        actionCell.append(wrap);
      } else {
        actionCell.textContent = skipped ? "—" : "";
      }
      row.append(actionCell);
      dom.writeBody.append(row);
    }
  }

  function writeEmptyRow(text) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 5;
    cell.textContent = text;
    row.append(cell);
    dom.writeBody.append(row);
  }

  function captureWriteDrafts() {
    if (!dom.writeBody) return;
    const bucket = writeDraftBucket(state.writeRenderedUid);
    for (const input of dom.writeBody.querySelectorAll("input[data-write-key]")) {
      bucket[input.getAttribute("data-write-key")] = input.value;
    }
  }

  function writeDraftBucket(uid) {
    const key = uid || "_none";
    if (!state.writeDrafts[key]) state.writeDrafts[key] = {};
    return state.writeDrafts[key];
  }

  function latestWriteCommand(paramKey) {
    return (state.writeCommands || []).find((item) => item.key === paramKey && item.status !== "superseded") || null;
  }

  function formatWriteCommandStatus(command) {
    if (!command) return "";
    if (command.status === "pending") return "ожидает приложение";
    if (command.status === "writing") return "записывается…";
    if (command.status === "done") {
      return command.actual == null ? "записано" : `записано: ${formatWriteDraft(command.actual)}`;
    }
    if (command.status === "failed") return command.error ? `ошибка: ${command.error}` : "ошибка записи";
    return command.status;
  }

  async function queueRemoteWrite(param, options) {
    const silent = Boolean(options && options.silent);
    const battery = getSelectedBattery();
    if (!battery || !battery.bms_uid) {
      if (!silent) showToast("Сначала выберите аккумулятор");
      return false;
    }
    captureWriteDrafts();
    const raw = writeDraftBucket(battery.bms_uid)[param.key];
    const value = parseWriteNumber(raw);
    if (value == null) {
      if (!silent) showToast("Введите значение для записи");
      return false;
    }
    if (!silent) {
      state.writeBusyKey = param.key;
      renderWritePanel(battery);
    }
    try {
      const body = await apiSend(
        "POST",
        `/api/v1/batteries/${encodeURIComponent(battery.bms_uid)}/write-commands`,
        { key: param.key, value },
      );
      if (body.command) {
        state.writeCommands = [body.command, ...(state.writeCommands || []).filter((item) => item.id !== body.command.id)];
      }
      if (!silent) showToast("Задание в очереди. Подключите BMS в приложении.");
      return true;
    } catch (error) {
      if (error && error.status === 401) {
        state.apiKey = "";
        localStorage.removeItem(API_KEY_STORAGE);
        openKeyDialog(true);
        return false;
      }
      if (!silent) {
        showToast(error && error.message ? `Не удалось поставить в очередь: ${error.message}` : "Не удалось поставить в очередь");
      }
      return false;
    } finally {
      if (!silent) {
        state.writeBusyKey = "";
        renderWritePanel(getSelectedBattery());
      }
    }
  }

  async function queueAllRemoteWrites() {
    const battery = getSelectedBattery();
    if (!battery || !battery.bms_uid) {
      showToast("Сначала выберите аккумулятор");
      return;
    }
    captureWriteDrafts();
    const skipDl = isRedDlSeries(battery);
    const params = templateParameters().filter((param) => !shouldSkipWriteParam(param, skipDl));
    const filled = params.filter((param) => parseWriteNumber(writeDraftBucket(battery.bms_uid)[param.key]) != null);
    if (filled.length === 0) {
      showToast("Нет заполненных значений для записи");
      return;
    }
    state.writeBusyKey = "*";
    renderWritePanel(battery);
    let queued = 0;
    let failed = 0;
    try {
      for (const param of filled) {
        const ok = await queueRemoteWrite(param, { silent: true });
        if (ok) queued += 1;
        else failed += 1;
      }
    } finally {
      state.writeBusyKey = "";
      renderWritePanel(getSelectedBattery());
    }
    if (queued && !failed) {
      showToast(`В очереди ${queued} параметров. Подключите BMS в приложении.`);
    } else if (queued) {
      showToast(`В очереди ${queued}, не удалось ${failed}`);
    } else {
      showToast("Не удалось поставить параметры в очередь");
    }
  }

  function templateParameters() {
    const template = state.configTemplate;
    return template && Array.isArray(template.parameters) ? template.parameters.filter((item) => item && item.key) : [];
  }

  function shouldSkipWriteParam(param, skipDl) {
    const enforcement = param && param.enforcement;
    if (enforcement === "info" || enforcement === "informational") return true;
    const skipFor = Array.isArray(param && param.skip_for) ? param.skip_for : [];
    return Boolean(skipDl && skipFor.includes("dl_red"));
  }

  function detectSeries(battery) {
    const check = battery && battery.config_check;
    const fromCheck = check ? numberOrNull(check.series_count) : null;
    if (fromCheck != null) return fromCheck;
    const latest = state.history[0] || battery;
    const count = sortedEntries(latest && latest.cells).length;
    return count > 0 ? count : null;
  }

  function expectedFromParam(param, series) {
    const bySeries = param && param.expected_by_series;
    if (bySeries && typeof bySeries === "object" && series != null) {
      const keyed = bySeries[String(series)];
      if (keyed != null) return keyed;
    }
    return param ? param.expected : undefined;
  }

  function formatExpectedDisplay(param, series) {
    const expected = expectedFromParam(param, series);
    if (expected != null) return formatConfigValue(expected, param.unit);
    if (param.expected_by_series && typeof param.expected_by_series === "object") {
      return Object.entries(param.expected_by_series)
        .map(([count, value]) => `${formatConfigValue(value, param.unit)} (${count}S)`)
        .join(" / ");
    }
    return formatConfigValue(param.expected, param.unit);
  }

  function updateWriteTemplateMeta(battery) {
    if (!dom.writeTemplateMeta) return;
    const template = state.configTemplate;
    if (!template) {
      dom.writeTemplateMeta.textContent = "Шаблон не загружен";
      return;
    }
    const series = detectSeries(battery);
    const parts = [
      template.version != null ? `Шаблон v${template.version}` : "Шаблон",
      series != null ? `${series}S` : null,
      isRedDlSeries(battery) ? "DL-серия" : null,
    ].filter(Boolean);
    dom.writeTemplateMeta.textContent = parts.join(" · ");
  }

  function updateWriteFileName() {
    if (!dom.writeFileName) return;
    dom.writeFileName.textContent = state.writeSourceName ? `Источник: ${state.writeSourceName}` : "";
  }

  function loadConfigTemplate(options) {
    const force = Boolean(options && options.force);
    if (state.configTemplate && !force) {
      if (options && options.fill) fillWriteValuesFromTemplate();
      return Promise.resolve(state.configTemplate);
    }
    if (state.templatePromise && !force) return state.templatePromise;

    const request = (async () => {
      try {
        const body = await apiGet("/api/v1/config-template");
        if (!body || !body.template || typeof body.template !== "object") {
          throw new Error("empty template");
        }
        state.configTemplate = body.template;
        const version = body.template.version != null ? ` v${body.template.version}` : "";
        state.writeSourceName = `Шаблон Liferych${version}`;
        updateWriteFileName();
        if (options && options.fill) fillWriteValuesFromTemplate();
        else renderWritePanel(getSelectedBattery());
        if (force) showToast("Шаблон загружен");
        return state.configTemplate;
      } catch (error) {
        if (error && error.name === "AbortError") return null;
        showToast("Не удалось загрузить шаблон");
        if (dom.writeTemplateMeta) dom.writeTemplateMeta.textContent = "Ошибка загрузки шаблона";
        return null;
      } finally {
        if (state.templatePromise === request) state.templatePromise = null;
      }
    })();

    state.templatePromise = request;
    return request;
  }

  function fillWriteValuesFromTemplate() {
    const params = templateParameters();
    if (params.length === 0) {
      showToast("Сначала загрузите шаблон");
      return;
    }
    const battery = getSelectedBattery();
    const skipDl = isRedDlSeries(battery);
    const series = detectSeries(battery);
    const bucket = writeDraftBucket(battery && battery.bms_uid);
    let filled = 0;
    let skippedSeries = 0;
    for (const param of params) {
      if (shouldSkipWriteParam(param, skipDl)) continue;
      const expected = expectedFromParam(param, series);
      if (expected == null) {
        if (param.expected_by_series) skippedSeries += 1;
        continue;
      }
      bucket[param.key] = formatWriteDraft(expected);
      filled += 1;
    }
    renderWritePanel(battery);
    if (filled === 0) {
      showToast(skippedSeries ? "Нет серии АКБ, pack-пороги не подставлены" : "В шаблоне нет значений");
      return;
    }
    showToast(skippedSeries ? `Подставлено ${filled}, серия АКБ не определена` : "Значения подставлены из шаблона");
  }

  async function handleWriteFileUpload(event) {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;
    let parsed;
    try {
      parsed = JSON.parse(await file.text());
    } catch {
      showToast("Не удалось разобрать JSON");
      return;
    }
    await applyUploadedConfig(parsed, file.name);
  }

  async function applyUploadedConfig(parsed, fileName) {
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      showToast("Файл не похож на конфиг");
      return;
    }

    const templateCandidate = parsed.template && typeof parsed.template === "object" ? parsed.template : parsed;
    const hasParams = Array.isArray(templateCandidate.parameters);
    if (hasParams) {
      state.configTemplate = templateCandidate;
    } else if (!state.configTemplate) {
      await loadConfigTemplate({ force: false });
    }

    const params = templateParameters();
    if (params.length === 0) {
      showToast("В файле нет параметров шаблона");
      return;
    }

    const snapshotRoot = parsed.config && typeof parsed.config === "object" ? parsed.config : parsed;
    const flat = flattenSnapshotValues(snapshotRoot);
    const looksLikeSnapshot = Boolean(parsed.config) || Object.keys(flat).some((key) => /_num$|_v$|_c$|_a$/.test(key) || key === "sleep_time_s");
    const battery = getSelectedBattery();
    const skipDl = isRedDlSeries(battery);
    const series = detectSeries(battery);
    const bucket = writeDraftBucket(battery && battery.bms_uid);
    let filled = 0;

    if (looksLikeSnapshot) {
      for (const param of params) {
        if (shouldSkipWriteParam(param, skipDl)) continue;
        const value = snapshotValueForKey(flat, param.key);
        if (value == null) continue;
        bucket[param.key] = formatWriteDraft(value);
        filled += 1;
      }
    } else if (hasParams) {
      for (const param of params) {
        if (shouldSkipWriteParam(param, skipDl)) continue;
        const expected = expectedFromParam(param, series);
        if (expected == null) continue;
        bucket[param.key] = formatWriteDraft(expected);
        filled += 1;
      }
    }

    state.writeSourceName = fileName;
    updateWriteFileName();
    renderWritePanel(battery);
    showToast(filled ? `Загружено: ${fileName}` : `Файл принят, совпадений нет`);
  }

  function flattenSnapshotValues(root) {
    const map = {};
    function walk(node) {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node)) {
        node.forEach(walk);
        return;
      }
      for (const [key, value] of Object.entries(node)) {
        if (value && typeof value === "object") walk(value);
        else map[key] = value;
      }
    }
    walk(root);
    return map;
  }

  function snapshotValueForKey(flat, key) {
    const aliases = [key, `${key}_num`, ...(WRITE_SNAPSHOT_ALIASES[key] || [])];
    for (const alias of aliases) {
      if (!(alias in flat)) continue;
      const parsed = parseWriteNumber(flat[alias]);
      if (parsed != null) return parsed;
    }
    return null;
  }

  function parseWriteNumber(value) {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value !== "string") return null;
    const match = value.replace(",", ".").match(/-?\d+(?:\.\d+)?/);
    if (!match) return null;
    const number = Number(match[0]);
    return Number.isFinite(number) ? number : null;
  }

  function formatWriteDraft(value) {
    const number = typeof value === "number" ? value : parseWriteNumber(value);
    return number == null ? "" : String(number);
  }

  function downloadWritePlan() {
    captureWriteDrafts();
    const battery = getSelectedBattery();
    const params = templateParameters();
    if (params.length === 0) {
      showToast("Нет параметров для задания");
      return;
    }
    const skipDl = isRedDlSeries(battery);
    const series = detectSeries(battery);
    const bucket = writeDraftBucket(battery && battery.bms_uid);
    const payload = {
      kind: "liferych-write-plan",
      created_at: new Date().toISOString(),
      bms_uid: battery ? battery.bms_uid : "",
      bluetooth_name: battery ? battery.bluetooth_name || battery.advertised_name || "" : "",
      series_count: series,
      template_id: state.configTemplate && state.configTemplate.id,
      template_version: state.configTemplate && state.configTemplate.version,
      source: state.writeSourceName || "template",
      parameters: params.map((param) => {
        const skipped = shouldSkipWriteParam(param, skipDl);
        return {
          key: param.key,
          label: param.label,
          register: param.register,
          unit: param.unit,
          expected: expectedFromParam(param, series) ?? null,
          value: skipped ? null : parseWriteNumber(bucket[param.key]),
          skipped,
        };
      }),
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    const slug = ((battery && battery.bms_uid) || "bms").replace(/[^\w.-]+/g, "_");
    link.href = url;
    link.download = `write-plan-${slug}.json`;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function drawHistoryChart() {
    const canvas = dom.historyChart;
    if (!canvas || state.dashboardTab !== "dynamics") return;

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
    return battery.bluetooth_name || battery.advertised_name || battery.bms_uid || "Аккумулятор";
  }

  function textOrEmpty(value) {
    return typeof value === "string" ? value.trim() : "";
  }

  function renderOwner(battery) {
    if (!dom.ownerCard) return;
    const name = textOrEmpty(battery.owner_name);
    const phone = textOrEmpty(battery.owner_phone);
    const email = textOrEmpty(battery.owner_email);
    const hasAny = Boolean(name || phone || email);
    const contacts = dom.ownerPhone ? dom.ownerPhone.parentElement : null;

    dom.ownerCard.classList.remove("hidden");
    if (dom.ownerName) {
      dom.ownerName.classList.toggle("hidden", !hasAny);
      dom.ownerName.textContent = name || "Имя не указано";
    }
    if (contacts) contacts.classList.toggle("hidden", !hasAny);
    if (dom.ownerEmpty) dom.ownerEmpty.classList.toggle("hidden", hasAny);
    if (!hasAny) return;

    setContactLink(dom.ownerPhone, phone, "tel:");
    setContactLink(dom.ownerEmail, email, "mailto:");
  }

  function setContactLink(node, value, prefix) {
    if (!node) return;
    if (!value) {
      node.textContent = "—";
      node.removeAttribute("href");
      return;
    }
    node.textContent = value;
    const hrefValue = prefix === "tel:" ? value.replace(/[^\d+]/g, "") : value;
    node.setAttribute("href", prefix + hrefValue);
  }

  function looksLikeRedDlName(value) {
    return typeof value === "string" && /^\s*DL/i.test(value);
  }

  function isRedDlSeries(_battery) {
    return false;
  }

  function renderHardwareBadge(battery) {
    if (!dom.hardwareBadge) return;
    if (!isRedDlSeries(battery)) {
      dom.hardwareBadge.classList.add("hidden");
      dom.hardwareBadge.textContent = "";
      return;
    }
    dom.hardwareBadge.classList.remove("hidden");
    dom.hardwareBadge.textContent = RED_DL_LABEL;
  }

  function filterDlRedItems(items) {
    return (Array.isArray(items) ? items : []).filter((item) => !DL_RED_SKIPPED_KEYS.has(item.key));
  }

  function visibleConfigCheck(battery) {
    const check = battery && battery.config_check;
    if (!check) return null;
    if (!isRedDlSeries(battery)) return check;

    const mismatches = filterDlRedItems(check.mismatches);
    const missing = filterDlRedItems(check.missing);
    let status = check.status;
    if (status === "mismatch" && mismatches.length === 0) {
      status = missing.length === 0 ? "ok" : "incomplete";
    } else if (status === "incomplete" && missing.length === 0 && mismatches.length === 0) {
      status = "ok";
    }
    return {
      ...check,
      status,
      mismatches,
      missing,
      mismatch_count: mismatches.length,
      missing_count: missing.length,
    };
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

  function formatMillivolts(value, withSign) {
    const number = numberOrNull(value);
    if (number == null) return "—";
    const mv = Math.round(number * 1000);
    if (mv === 0) return "0 мВ";
    const signed = withSign && mv > 0 ? `+${mv}` : String(mv);
    return `${signed} мВ`;
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
