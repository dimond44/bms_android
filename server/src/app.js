const express = require("express");
const fs = require("node:fs");
const path = require("node:path");

const WRITE_COMMAND_STALE_MS = 120000;

const NUMERIC_FIELDS = [
  "voltage",
  "current",
  "soc",
  "remaining_ah",
  "estimated_full_ah",
  "nominal_capacity_ah",
  "cell_diff_v",
  "min_cell_v",
  "max_cell_v",
  "min_temp",
  "max_temp",
];

function createApp({ database, apiKey }) {
  if (!database) throw new TypeError("database is required");
  if (typeof apiKey !== "string" || apiKey.length === 0) {
    throw new TypeError("apiKey must be a non-empty string");
  }

  const app = express();
  const publicDir = path.join(__dirname, "..", "public");
  app.disable("x-powered-by");
  app.use(express.json({ limit: "2mb" }));
  app.use(express.static(publicDir));

  app.get("/health", (_request, response) => {
    response.json({ ok: true });
  });

  const receiveTelemetry = (request, response) => {
    const suppliedKey = request.get("x-api-key") || request.body?.api_key;
    if (suppliedKey !== apiKey) {
      console.warn("telemetry unauthorized from", request.ip);
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }

    const validationError = validateTelemetry(request.body);
    if (validationError) {
      console.warn("telemetry rejected", validationError);
      return response.status(400).json({ ok: false, error: validationError });
    }

    const payload = { ...request.body };
    delete payload.api_key;

    const logId = database.insertTelemetry(payload);
    const uid = typeof payload.bms_uid === "string" ? payload.bms_uid.split("\0")[0] : payload.bms_uid;
    console.log(`telemetry uid=${uid} source=${payload.source || "-"} log_id=${logId}`);
    return response.status(201).json({ ok: true, log_id: logId });
  };

  app.post("/api/v1/telemetry", receiveTelemetry);
  app.post("/api/upload.php", receiveTelemetry);

  const receiveConfigCheck = (request, response) => {
    const suppliedKey = request.get("x-api-key") || request.body?.api_key;
    if (suppliedKey !== apiKey) {
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }

    const validationError = validateConfigCheck(request.body);
    if (validationError) {
      return response.status(400).json({ ok: false, error: validationError });
    }

    const payload = { ...request.body };
    delete payload.api_key;

    const checkId = database.insertConfigCheck(payload);
    return response.status(201).json({ ok: true, check_id: checkId });
  };

  app.post("/api/v1/config-check", receiveConfigCheck);
  app.post("/api/config_upload.php", receiveConfigCheck);

  const requireHeaderApiKey = (request, response, next) => {
    if (request.get("x-api-key") !== apiKey) {
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }
    return next();
  };

  app.get("/api/v1/batteries", (_request, response) => {
    response.json({ ok: true, batteries: database.listBatteries() });
  });

  app.get("/api/v1/config-template", requireHeaderApiKey, (_request, response) => {
    try {
      return response.json({ ok: true, template: loadConfigTemplate() });
    } catch (error) {
      if (error && error.code === "TEMPLATE_NOT_FOUND") {
        return response.status(404).json({ ok: false, error: "template_not_found" });
      }
      console.error(error);
      return response.status(500).json({ ok: false, error: "template_read_error" });
    }
  });

  app.get(
    "/api/v1/batteries/:bmsUid/telemetry",
    (request, response) => {
      const bmsUid = request.params.bmsUid;
      if (!database.hasBattery(bmsUid)) {
        return response.status(404).json({ ok: false, error: "battery_not_found" });
      }

      const limit = parseLimit(request.query.limit);
      if (limit == null) {
        return response.status(400).json({ ok: false, error: "invalid_limit" });
      }

      return response.json({
        ok: true,
        bms_uid: bmsUid,
        telemetry: database.listTelemetry(bmsUid, limit),
      });
    },
  );

  app.get(
    "/api/v1/batteries/:bmsUid/config-checks",
    (request, response) => {
      const bmsUid = request.params.bmsUid;
      if (!database.hasBattery(bmsUid)) {
        return response.status(404).json({ ok: false, error: "battery_not_found" });
      }

      const limit = parseLimit(request.query.limit);
      if (limit == null) {
        return response.status(400).json({ ok: false, error: "invalid_limit" });
      }

      return response.json({
        ok: true,
        bms_uid: bmsUid,
        config_checks: database.listConfigChecks(bmsUid, limit),
      });
    },
  );

  const requireAnyApiKey = (request, response, next) => {
    const supplied = request.get("x-api-key") || request.body?.api_key || request.query.api_key;
    if (supplied !== apiKey) {
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }
    return next();
  };

  app.get("/api/v1/batteries/:bmsUid/write-commands", requireAnyApiKey, (request, response) => {
    const bmsUid = request.params.bmsUid;
    if (!database.hasBattery(bmsUid)) {
      return response.status(404).json({ ok: false, error: "battery_not_found" });
    }
    const limit = parseLimit(request.query.limit ?? "20");
    if (limit == null) {
      return response.status(400).json({ ok: false, error: "invalid_limit" });
    }
    const status = typeof request.query.status === "string" ? request.query.status : undefined;
    return response.json({
      ok: true,
      bms_uid: bmsUid,
      commands: database.listWriteCommands(bmsUid, {
        status,
        limit,
        staleBefore: Date.now() - WRITE_COMMAND_STALE_MS,
      }),
    });
  });

  app.post("/api/v1/batteries/:bmsUid/write-commands", requireAnyApiKey, (request, response) => {
    const bmsUid = request.params.bmsUid;
    if (!database.hasBattery(bmsUid)) {
      return response.status(404).json({ ok: false, error: "battery_not_found" });
    }

    const key = typeof request.body?.key === "string" ? request.body.key.trim() : "";
    const value = request.body?.value;
    if (!key) {
      return response.status(400).json({ ok: false, error: "invalid_key" });
    }
    if (!isFiniteNumber(value)) {
      return response.status(400).json({ ok: false, error: "invalid_value" });
    }

    let template;
    try {
      template = loadConfigTemplate();
    } catch (error) {
      if (error && error.code === "TEMPLATE_NOT_FOUND") {
        return response.status(404).json({ ok: false, error: "template_not_found" });
      }
      console.error(error);
      return response.status(500).json({ ok: false, error: "template_read_error" });
    }

    const param = (Array.isArray(template.parameters) ? template.parameters : []).find((item) => item.key === key);
    if (!param || typeof param.register !== "string") {
      return response.status(400).json({ ok: false, error: "unknown_param" });
    }
    if (param.enforcement === "info" || param.enforcement === "informational") {
      return response.status(400).json({ ok: false, error: "not_writable" });
    }

    const skipFor = Array.isArray(param.skip_for) ? param.skip_for : [];
    if ((skipFor.includes("r10k") || skipFor.includes("tk10")) && isR10kBattery(database.getBattery(bmsUid))) {
      return response.status(400).json({ ok: false, error: "skipped_for_r10k" });
    }

    const scale = Number(param.scale);
    const offset = Number(param.offset) || 0;
    if (!Number.isFinite(scale) || scale === 0) {
      return response.status(400).json({ ok: false, error: "invalid_scale" });
    }

    const rawValue = Math.round((value - offset) * scale);
    if (!Number.isInteger(rawValue) || rawValue < 0 || rawValue > 0xffff) {
      return response.status(400).json({ ok: false, error: "value_out_of_range" });
    }

    const commandId = database.enqueueWriteCommand({
      bms_uid: bmsUid,
      param_key: key,
      label: param.label || key,
      register: param.register,
      unit: param.unit || null,
      value,
      raw_value: rawValue,
      scale,
      offset,
    });

    return response.status(201).json({
      ok: true,
      command: database.getWriteCommand(commandId),
    });
  });

  app.post("/api/v1/batteries/:bmsUid/write-commands/:id/ack", requireAnyApiKey, (request, response) => {
    const bmsUid = request.params.bmsUid;
    const id = Number.parseInt(request.params.id, 10);
    if (!Number.isInteger(id) || id <= 0) {
      return response.status(400).json({ ok: false, error: "invalid_id" });
    }

    const current = database.getWriteCommand(id);
    if (!current || current.bms_uid !== bmsUid) {
      return response.status(404).json({ ok: false, error: "command_not_found" });
    }

    const status = request.body?.status;
    if (status === "writing") {
      database.claimWriteCommand(id);
      return response.json({ ok: true, command: database.getWriteCommand(id) });
    }
    if (status !== "done" && status !== "failed") {
      return response.status(400).json({ ok: false, error: "invalid_status" });
    }

    const actual = request.body?.actual;
    if (actual != null && !isFiniteNumber(actual)) {
      return response.status(400).json({ ok: false, error: "invalid_actual" });
    }
    const errorText = typeof request.body?.error === "string" ? request.body.error.slice(0, 300) : null;

    const updated = database.updateWriteCommand(id, {
      status,
      actual: actual == null ? null : actual,
      error: status === "failed" ? errorText : null,
    });
    return response.json({ ok: true, command: updated });
  });

  const receiveServiceReport = (request, response) => {
    const suppliedKey = request.get("x-api-key") || request.body?.api_key;
    if (suppliedKey !== apiKey) {
      console.warn("service-report unauthorized from", request.ip);
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }

    const validationError = validateServiceReport(request.body);
    if (validationError) {
      console.warn("service-report rejected", validationError);
      return response.status(400).json({ ok: false, error: validationError });
    }

    const payload = { ...request.body };
    delete payload.api_key;

    const reportId = database.insertServiceReport(payload);
    console.log(`service-report uid=${payload.bms_uid} assembler=${payload.assembler_name} report_id=${reportId}`);
    return response.status(201).json({ ok: true, report_id: reportId });
  };

  app.post("/api/v1/service-report", receiveServiceReport);
  app.post("/api/service_report.php", receiveServiceReport);

  app.get(
    "/api/v1/batteries/:bmsUid/service-reports",
    (request, response) => {
      const bmsUid = request.params.bmsUid;
      if (!database.hasBattery(bmsUid)) {
        return response.status(404).json({ ok: false, error: "battery_not_found" });
      }

      const limit = parseLimit(request.query.limit);
      if (limit == null) {
        return response.status(400).json({ ok: false, error: "invalid_limit" });
      }

      return response.json({
        ok: true,
        bms_uid: bmsUid,
        service_reports: database.listServiceReports(bmsUid, limit),
      });
    },
  );

  app.use((_request, response) => {
    response.status(404).json({ ok: false, error: "not_found" });
  });

  app.use((error, _request, response, _next) => {
    if (error?.type === "entity.too.large") {
      return response.status(413).json({ ok: false, error: "payload_too_large" });
    }
    if (error instanceof SyntaxError && error.status === 400 && "body" in error) {
      return response.status(400).json({ ok: false, error: "invalid_json" });
    }

    console.error(error);
    return response.status(500).json({ ok: false, error: "internal_error" });
  });

  return app;
}

function validateTelemetry(payload) {
  if (!isPlainObject(payload)) return "payload_must_be_object";
  if (typeof payload.bms_uid !== "string" || payload.bms_uid.trim().length === 0) {
    return "invalid_bms_uid";
  }

  for (const field of NUMERIC_FIELDS) {
    if (payload[field] != null && !isFiniteNumber(payload[field])) {
      return `invalid_${field}`;
    }
  }

  for (const field of ["charge_mos", "discharge_mos"]) {
    if (payload[field] != null && typeof payload[field] !== "boolean") {
      return `invalid_${field}`;
    }
  }

  for (const field of ["cells", "temps"]) {
    if (payload[field] != null) {
      if (!isPlainObject(payload[field])) return `invalid_${field}`;
      if (Object.values(payload[field]).some((value) => !isFiniteNumber(value))) {
        return `invalid_${field}`;
      }
    }
  }

  if (payload.raw != null && !isPlainObject(payload.raw)) return "invalid_raw";
  if (payload.errors != null && !Array.isArray(payload.errors)) return "invalid_errors";
  if (payload.events != null && !Array.isArray(payload.events)) return "invalid_events";
  if (payload.advertised_name != null && typeof payload.advertised_name !== "string") {
    return "invalid_advertised_name";
  }
  if (payload.hardware_family != null && typeof payload.hardware_family !== "string") {
    return "invalid_hardware_family";
  }
  for (const field of ["owner_name", "owner_phone", "owner_email", "assembler_name", "bms_sn", "bms_version", "bms_battery_code", "bms_hw_version", "bluetooth_id", "source"]) {
    if (payload[field] != null && typeof payload[field] !== "string") {
      return `invalid_${field}`;
    }
  }

  return null;
}

function validateConfigCheck(payload) {
  if (!isPlainObject(payload)) return "payload_must_be_object";
  if (typeof payload.bms_uid !== "string" || payload.bms_uid.trim().length === 0) {
    return "invalid_bms_uid";
  }
  if (payload.bluetooth_name != null && typeof payload.bluetooth_name !== "string") {
    return "invalid_bluetooth_name";
  }
  if (payload.advertised_name != null && typeof payload.advertised_name !== "string") {
    return "invalid_advertised_name";
  }
  if (payload.hardware_family != null && typeof payload.hardware_family !== "string") {
    return "invalid_hardware_family";
  }
  for (const field of ["owner_name", "owner_phone", "owner_email"]) {
    if (payload[field] != null && typeof payload[field] !== "string") {
      return `invalid_${field}`;
    }
  }
  if (payload.bluetooth_address != null && typeof payload.bluetooth_address !== "string") {
    return "invalid_bluetooth_address";
  }
  if (payload.config != null && !isPlainObject(payload.config)) return "invalid_config";
  if (payload.raw != null && !isPlainObject(payload.raw)) return "invalid_raw";

  const check = payload.template_check;
  if (!isPlainObject(check)) return "invalid_template_check";
  if (typeof check.template_id !== "string" || check.template_id.trim().length === 0) {
    return "invalid_template_id";
  }
  if (!isNonNegativeInteger(check.template_version)) return "invalid_template_version";
  if (!["ok", "mismatch", "incomplete"].includes(check.status)) return "invalid_status";
  if (!isNonNegativeInteger(check.checked_at)) return "invalid_checked_at";
  if (check.series_count !== null && !isNonNegativeInteger(check.series_count)) {
    return "invalid_series_count";
  }
  if (!isNonNegativeInteger(check.mismatch_count)) return "invalid_mismatch_count";
  if (!isNonNegativeInteger(check.missing_count)) return "invalid_missing_count";

  for (const field of ["mismatches", "missing", "unverified"]) {
    if (!Array.isArray(check[field])) return `invalid_${field}`;
    for (const item of check[field]) {
      if (!isConfigCheckItem(item)) return `invalid_${field}`;
      if (field !== "mismatches" && typeof item.reason !== "string") {
        return `invalid_${field}`;
      }
      if (item.unit != null && typeof item.unit !== "string") return `invalid_${field}`;
      if (item.tolerance != null && !isFiniteNumber(item.tolerance)) {
        return `invalid_${field}`;
      }
    }
  }

  return null;
}

function isConfigCheckItem(item) {
  return isPlainObject(item)
    && typeof item.key === "string"
    && item.key.trim().length > 0
    && typeof item.label === "string"
    && item.label.trim().length > 0;
}

function validateServiceReport(payload) {
  if (!isPlainObject(payload)) return "payload_must_be_object";
  if (typeof payload.bms_uid !== "string" || payload.bms_uid.trim().length === 0) {
    return "invalid_bms_uid";
  }
  if (typeof payload.assembler_name !== "string" || payload.assembler_name.trim().length === 0) {
    return "invalid_assembler_name";
  }
  if (typeof payload.template_id !== "string" || payload.template_id.trim().length === 0) {
    return "invalid_template_id";
  }
  if (!["ok", "partial", "failed"].includes(payload.status)) return "invalid_status";
  if (payload.capacity_ah != null && !isFiniteNumber(payload.capacity_ah)) {
    return "invalid_capacity_ah";
  }
  if (payload.written_at != null && !isNonNegativeInteger(payload.written_at)) {
    return "invalid_written_at";
  }
  for (const field of ["bms_sn", "bms_version", "bms_battery_code", "bms_hw_version", "bluetooth_id", "bluetooth_name", "bluetooth_address", "source"]) {
    if (payload[field] != null && typeof payload[field] !== "string") {
      return `invalid_${field}`;
    }
  }
  if (payload.items != null) {
    if (!Array.isArray(payload.items)) return "invalid_items";
    for (const item of payload.items) {
      if (!isPlainObject(item)) return "invalid_items";
      if (typeof item.key !== "string" || item.key.trim().length === 0) return "invalid_items";
      if (typeof item.label !== "string" || item.label.trim().length === 0) return "invalid_items";
      if (typeof item.ok !== "boolean") return "invalid_items";
      if (item.expected != null && !isFiniteNumber(item.expected)) return "invalid_items";
      if (item.actual != null && !isFiniteNumber(item.actual)) return "invalid_items";
      if (item.unit != null && typeof item.unit !== "string") return "invalid_items";
      if (item.error != null && typeof item.error !== "string") return "invalid_items";
    }
  }
  return null;
}

function parseLimit(value) {
  if (value === undefined) return 100;
  if (typeof value !== "string" || !/^\d+$/.test(value)) return null;
  const limit = Number(value);
  return limit >= 1 && limit <= 1000 ? limit : null;
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function loadConfigTemplate() {
  const templatePath = path.resolve(__dirname, "..", "..", "config", "bms_config_template.json");
  if (!fs.existsSync(templatePath)) {
    const error = new Error("template_not_found");
    error.code = "TEMPLATE_NOT_FOUND";
    throw error;
  }
  return JSON.parse(fs.readFileSync(templatePath, "utf8"));
}

function parseBmsHardwareVersion(battery) {
  const tokens = ["R24TK", "R24TH", "R10K"];
  const haystack = [
    battery && battery.bms_hw_version,
    battery && battery.bms_version,
    battery && battery.bms_battery_code,
    battery && battery.bms_sn,
  ].map((value) => String(value || "").toUpperCase()).join(" ");
  let bestIndex = Number.POSITIVE_INFINITY;
  let bestToken = "";
  for (const token of tokens) {
    const index = haystack.indexOf(token);
    if (index >= 0 && index < bestIndex) {
      bestIndex = index;
      bestToken = token;
    }
  }
  return bestToken;
}

function isR10kBattery(battery) {
  if (!battery) return true;
  const hw = String((battery && (battery.bms_hw_version || battery.bms_version)) || "").toUpperCase();
  if (hw) return !hw.includes("R24");
  return !parseBmsHardwareVersion(battery).startsWith("R24");
}

module.exports = { createApp };
