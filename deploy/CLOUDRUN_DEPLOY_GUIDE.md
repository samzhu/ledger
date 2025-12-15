# Cloud Run + OpenTelemetry Collector Sidecar 部署指南

> 在 **Google Cloud Shell** 中執行所有指令

---

## 架構圖

```
                            ┌─────────────────────────────────────────────────────────────┐
         Ingress            │                      Cloud Run Service                       │
      ────────────────────► │  ┌──────────────────┐    OTLP     ┌───────────────────────┐ │
                            │  │   Ledger App     │ ──────────► │  OTel Collector       │ │
                            │  │   (port 8080)    │  :4317/4318 │  Sidecar              │ │
                            │  └────────┬─────────┘             └───────────┬───────────┘ │
                            └───────────┼───────────────────────────────────┼─────────────┘
                                        │                                   │
                                        ▼                                   ▼
                            ┌─────────────────────────────────────────────────────────────┐
                            │                      Google Cloud                            │
                            │  ┌─────────────────┐  ┌─────────────────────────────────┐   │
                            │  │   Pub/Sub       │  │  Observability                  │   │
                            │  │   (訂閱用量事件) │  │  • Cloud Trace (追蹤)           │   │
                            │  └────────┬────────┘  │  • Cloud Monitoring (指標)      │   │
                            │           │           │  • Cloud Logging (日誌)         │   │
                            │           ▼           └─────────────────────────────────┘   │
                            │  ┌─────────────────┐                                        │
                            │  │   Firestore     │                                        │
                            │  │   (MongoDB API) │                                        │
                            │  └─────────────────┘                                        │
                            └─────────────────────────────────────────────────────────────┘
```

---

## 部署流程總覽

| 階段 | 步驟 | 說明 |
|------|------|------|
| **環境設定** | Step 0-3 | 確認環境、設定變數、啟用 API |
| **Pub/Sub 設定** | Step 3-A | 建立 Pub/Sub Subscription (訂閱用量事件) |
| **服務帳戶** | Step 4 | 建立服務帳戶並授權 |
| **準備檔案** | Step 5-6 | 建立工作目錄、準備 OTel 與應用程式配置檔 |
| **建立 Secrets** | Step 7 | 將所有配置檔與機敏值存入 Secret Manager |
| **建立部署檔** | Step 8 | 產生 Cloud Run Service YAML |
| **部署與驗證** | Step 9-11 | 部署服務、設定存取權限、驗證結果 |

---

## 前置需求

在執行部署前，請確保已完成以下步驟：

1. **Firestore MongoDB 相容資料庫** - 參考 [FIRESTORE-MONGODB-SETUP.md](./FIRESTORE-MONGODB-SETUP.md)
2. **Secret Manager 中的 `ledger-mongodb-uri`** - 參考上述文件步驟五

---

## Step 0: 確認 gcloud 環境

```bash
# 查看 gcloud 版本
gcloud version

# 查看目前登入帳號
gcloud auth list

# 查看目前所在專案
gcloud config get-value project

# 列出可用專案
gcloud projects list

# 切換專案 (如需要)
# gcloud config set project YOUR_PROJECT_ID
```

---

## Step 1: 定義環境變數

### 可配置變數一覽

| 變數名稱 | 說明 | 範例值 |
|---------|------|--------|
| `PROJECT_ID` | GCP 專案 ID | (自動取得) |
| `REGION` | Cloud Run 部署區域 | `asia-east1` |
| `SERVICE_NAME` | Cloud Run 服務名稱 | `ledger` |
| `ENV_PROFILE` | 環境 profile 名稱 | `lab` 或 `prod` |
| `APP_IMAGE` | 應用程式 Docker 映像 | `spike19820318/ledger:0.0.3` |
| `APP_PORT` | 應用程式監聽埠 | `8080` |
| `APP_CPU` | 應用程式 CPU 限制 | `1000m` |
| `APP_MEMORY` | 應用程式記憶體限制 | `1Gi` |
| `OTEL_COLLECTOR_IMAGE` | OTel Collector 映像 | `us-docker.pkg.dev/.../otelcol-google:0.138.0` |
| `OTEL_CPU` | Collector CPU 限制 | `500m` |
| `OTEL_MEMORY` | Collector 記憶體限制 | `512Mi` |
| `OTEL_LOG_NAME` | Cloud Logging 日誌名稱 | `${SERVICE_NAME}` |
| `MAX_INSTANCES` | 最大實例數 | `1` |
| `CONTAINER_CONCURRENCY` | 容器並發請求數 | `80` |
| `OTEL_SECRET_NAME` | OTel Collector 配置密鑰名稱 | `otel-collector-config` |
| `CONFIG_SECRET_NAME` | 應用程式配置密鑰名稱 | `ledger-config` |
| `SERVICE_ACCOUNT_ID` | 服務帳戶 ID | `ledger-sa` |

### 設定變數

```bash
# ==================================================
# GCP 專案設定 (自動從 gcloud 取得)
# ==================================================
export PROJECT_ID=$(gcloud config get-value project)
export REGION="asia-east1"

# ==================================================
# 服務設定
# ==================================================
export SERVICE_NAME="ledger"
# 環境 profile: lab, prod 等 (每個 GCP Project 為獨立環境)
export ENV_PROFILE="lab"

# ==================================================
# Secret Manager 設定 (名稱不需環境後綴，因為每個專案獨立)
# ==================================================
export OTEL_SECRET_NAME="otel-collector-config"
export CONFIG_SECRET_NAME="ledger-config"

# ==================================================
# 應用程式容器設定
# ==================================================
export APP_IMAGE="docker.io/spike19820318/ledger:0.0.2"
export APP_PORT="8080"
export APP_CPU="1000m"
export APP_MEMORY="1Gi"

# ==================================================
# OpenTelemetry Collector 設定
# ==================================================
# 映像版本參考: https://github.com/GoogleCloudPlatform/opentelemetry-operations-collector/releases
export OTEL_COLLECTOR_IMAGE="us-docker.pkg.dev/cloud-ops-agents-artifacts/google-cloud-opentelemetry-collector/otelcol-google:0.138.0"
export OTEL_CPU="500m"
export OTEL_MEMORY="512Mi"
# OTEL_LOG_NAME: 設定 Cloud Logging 中的日誌名稱
export OTEL_LOG_NAME="${SERVICE_NAME}"

# ==================================================
# 擴展設定
# ==================================================
export MAX_INSTANCES="1"
export CONTAINER_CONCURRENCY="80"

# ==================================================
# 服務帳戶設定
# ==================================================
export SERVICE_ACCOUNT_ID="ledger-sa"
export SERVICE_ACCOUNT="${SERVICE_ACCOUNT_ID}@${PROJECT_ID}.iam.gserviceaccount.com"

# 驗證變數
echo "=========================================="
echo "部署配置確認"
echo "=========================================="
echo "PROJECT_ID:          $PROJECT_ID"
echo "REGION:              $REGION"
echo "SERVICE_NAME:        $SERVICE_NAME"
echo "ENV_PROFILE:         $ENV_PROFILE"
echo "APP_IMAGE:           $APP_IMAGE"
echo "CONFIG_SECRET:       $CONFIG_SECRET_NAME"
echo "OTEL_SECRET:         $OTEL_SECRET_NAME"
echo "SERVICE_ACCOUNT:     $SERVICE_ACCOUNT"
echo "=========================================="
```

---

## Step 2: 設定 GCP 專案 (如需切換)

> **Cloud Shell 用戶**：如果命令列已顯示正確專案，可跳過此步驟。

```bash
# 僅在需要切換專案時執行
gcloud config set project $PROJECT_ID
```

---

## Step 3: 啟用 API

啟用 Cloud Run 部署和可觀測性所需的 Google Cloud API：

| API | 說明 |
|-----|------|
| `run.googleapis.com` | Cloud Run 服務，用於部署和執行容器 |
| `secretmanager.googleapis.com` | Secret Manager，安全儲存配置檔與機敏值 |
| `cloudtrace.googleapis.com` | Cloud Trace，收集分散式追蹤資料 |
| `monitoring.googleapis.com` | Cloud Monitoring，收集指標資料 |
| `logging.googleapis.com` | Cloud Logging，收集日誌資料 |
| `iam.googleapis.com` | IAM，管理服務帳戶和權限 |
| `pubsub.googleapis.com` | Pub/Sub，用於訂閱用量事件訊息 |
| `firestore.googleapis.com` | Firestore，用於資料儲存 |

```bash
gcloud services enable \
  run.googleapis.com \
  secretmanager.googleapis.com \
  cloudtrace.googleapis.com \
  monitoring.googleapis.com \
  logging.googleapis.com \
  iam.googleapis.com \
  pubsub.googleapis.com \
  firestore.googleapis.com
```

---

## Step 3-A: Pub/Sub Subscription 說明

Ledger 應用程式使用 Spring Cloud GCP Pub/Sub Stream Binder 訂閱 Topic `llm-gateway-usage` 的用量事件。

### Subscription 命名規則

Spring Cloud Stream 會自動建立 Subscription，命名規則為：`{destination}.{group}`

根據 `application.yaml` 配置：
- `destination`: `llm-gateway-usage`
- `group`: `ledger`
- **自動建立的 Subscription 名稱**: `llm-gateway-usage.ledger`

### 選項 A: 自動建立 (開發/測試環境)

不需要手動建立，應用程式啟動時會自動建立 Subscription。

→ 權限設定請見 **Step 4.4**（選擇 `roles/pubsub.editor`）

### 選項 B: 手動建立 (生產環境建議)

預先建立 Subscription，符合最小權限原則：

```bash
# 建立 Pub/Sub Subscription (假設 Topic 已由 gate 服務建立)
gcloud pubsub subscriptions create llm-gateway-usage.ledger \
  --topic=llm-gateway-usage \
  --ack-deadline=60 \
  --project=$PROJECT_ID

echo "✅ Pub/Sub Subscription 已建立: llm-gateway-usage.ledger"
```

→ 權限設定請見 **Step 4.4**（選擇 `roles/pubsub.subscriber`）

---

## Step 4: 建立服務帳戶並授權

### 4.1 檢查服務帳戶是否已存在

```bash
gcloud iam service-accounts describe $SERVICE_ACCOUNT 2>/dev/null || echo "服務帳戶不存在，將建立新的"
```

### 4.2 建立服務帳戶

```bash
gcloud iam service-accounts create $SERVICE_ACCOUNT_ID \
  --display-name="Cloud Run Service Account for ${SERVICE_NAME}" \
  --description="Service account for ${SERVICE_NAME} on Cloud Run" \
  --project=$PROJECT_ID
```

### 4.3 授予必要角色

```bash
# Cloud Run 執行所需的基本權限
for ROLE in \
  "roles/logging.logWriter" \
  "roles/monitoring.metricWriter" \
  "roles/cloudtrace.agent" \
  "roles/datastore.user"
do
  echo "授予角色: $ROLE"
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SERVICE_ACCOUNT" \
    --role="$ROLE" \
    --condition=None \
    --quiet
done

echo "✅ 基本權限設定完成"
```

### 4.4 授予 Pub/Sub 權限

根據 Step 3-A 選擇的方式授予對應權限：

```bash
# 選項 A: 自動建立 Subscription (開發/測試環境)
PUBSUB_ROLE="roles/pubsub.editor"

# 選項 B: 手動建立 Subscription (生產環境，最小權限)
# PUBSUB_ROLE="roles/pubsub.subscriber"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="$PUBSUB_ROLE" \
  --condition=None \
  --quiet

echo "✅ Pub/Sub 權限設定完成: $PUBSUB_ROLE"
```

### 4.5 授予 Secret Manager 權限

授予存取所有必要 secrets 的權限：

```bash
# 授予存取所有部署所需 secrets 的權限
for SECRET_NAME in "ledger-mongodb-uri" "ledger-config" "otel-collector-config"
do
  echo "授予 Secret 存取權限: $SECRET_NAME"
  gcloud secrets add-iam-policy-binding $SECRET_NAME \
    --member="serviceAccount:$SERVICE_ACCOUNT" \
    --role="roles/secretmanager.secretAccessor" \
    --project=$PROJECT_ID \
    --quiet 2>/dev/null || echo "  (Secret $SECRET_NAME 尚未建立，將在 Step 7 建立後自動授權)"
done

echo "✅ Secret Manager 權限設定完成"
```

> **注意**：`ledger-config` 和 `otel-collector-config` 會在 Step 7 建立，若尚未建立會顯示警告，部署前需確保已授權。

### 4.6 驗證服務帳戶

```bash
# 查看服務帳戶資訊
gcloud iam service-accounts describe $SERVICE_ACCOUNT

# 查看已授予的角色
gcloud projects get-iam-policy $PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:$SERVICE_ACCOUNT" \
  --format="table(bindings.role)"
```

---

## Step 5: 建立工作目錄

```bash
mkdir -p ~/cloudrun-deploy && cd ~/cloudrun-deploy
```

---

## Step 6: 準備配置檔

在此步驟準備所有配置檔，下一步會統一存入 Secret Manager。

### 6.1 建立 OTel Collector 配置檔

建立 OpenTelemetry Collector 配置檔，定義如何接收、處理和導出遙測資料：

| 區塊 | 說明 |
|------|------|
| `receivers.otlp` | 接收 OTLP 協議資料 (gRPC: 4317, HTTP: 4318) |
| `processors.batch` | 批次處理，減少 API 呼叫次數 |
| `processors.memory_limiter` | 記憶體限制，防止 OOM |
| `processors.resourcedetection` | 自動偵測 GCP 資源屬性 |
| `exporters.googlecloud` | 導出 Traces 和 Logs 到 Cloud Trace / Cloud Logging |
| `exporters.googlemanagedprometheus` | 導出 Metrics 到 Cloud Monitoring |
| `extensions.health_check` | 健康檢查端點 (port 13133) |

```bash
cat > otel-collector-config.yaml << EOF
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
processors:
  batch:
    send_batch_size: 200
    timeout: 5s
  memory_limiter:
    check_interval: 1s
    limit_percentage: 65
    spike_limit_percentage: 20
  resourcedetection:
    detectors: [env, gcp]
    timeout: 2s
    override: false
exporters:
  googlecloud:
    log:
      default_log_name: $OTEL_LOG_NAME
  googlemanagedprometheus:
extensions:
  health_check:
    endpoint: 0.0.0.0:13133
service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlemanagedprometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
EOF

echo "✅ 已建立 otel-collector-config.yaml"
```

### 6.2 建立應用程式配置檔

此配置檔會被 Cloud Run 掛載到 `/config/application-{profile}.yaml`，Spring Boot 透過 `spring.config.additional-location` 自動載入。

> **注意**: 配置檔中使用 `${sm@secret-name}` 語法，讓 Spring Cloud GCP 在執行時從 Secret Manager 讀取機敏值。


應用程式配置 - 環境特定設定  

此檔案透過 Secret Manager 掛載到 /config/  
機敏值使用 ${sm@secret-name} 語法，由 Spring Cloud GCP 在執行時解析  

```bash
cat > application-${ENV_PROFILE}.yaml << 'APPEOF'

# 機敏屬性來源 - 從 Secret Manager 讀取
ledger-mongodb-uri: ${sm@ledger-mongodb-uri}

# 可觀測性 - 全量取樣便於除錯
management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        # 注意: 移除 env,configprops 避免洩漏 secrets
        include: health,info,metrics,prometheus

# 日誌
logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
APPEOF

echo "✅ 已建立 application-${ENV_PROFILE}.yaml"
```

### 6.3 確認配置檔已建立

```bash
ls -la *.yaml
```

---

## Step 7: 建立 Secret Manager 密鑰

將所有配置檔存入 Secret Manager。

> **注意**: `ledger-mongodb-uri` 應已在 [FIRESTORE-MONGODB-SETUP.md](./FIRESTORE-MONGODB-SETUP.md) 中建立。

### 7.1 建立 OTel Collector 配置 Secret

```bash
gcloud secrets describe $OTEL_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID || \
  gcloud secrets create $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 授權 Service Account 存取
gcloud secrets add-iam-policy-binding $OTEL_SECRET_NAME \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="roles/secretmanager.secretAccessor" \
  --project=$PROJECT_ID
```

### 7.2 建立應用程式配置 Secret

```bash
gcloud secrets describe $CONFIG_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --project=$PROJECT_ID || \
  gcloud secrets create $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 授權 Service Account 存取
gcloud secrets add-iam-policy-binding $CONFIG_SECRET_NAME \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="roles/secretmanager.secretAccessor" \
  --project=$PROJECT_ID
```

### 7.3 驗證所有 Secrets

```bash
echo "=== Secrets 清單 ==="
gcloud secrets list --project=$PROJECT_ID --filter="name~ledger"

echo ""
echo "=== OTel 配置版本 ==="
gcloud secrets versions list $OTEL_SECRET_NAME --project=$PROJECT_ID

echo ""
echo "=== 應用程式配置版本 ==="
gcloud secrets versions list $CONFIG_SECRET_NAME --project=$PROJECT_ID
```

---

## Step 8: 建立 Cloud Run Service YAML

建立 Cloud Run 服務定義，包含主應用容器和 OTel Collector Sidecar。

### 關鍵 Annotations 說明

**Service 層級** (`metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/launch-stage: BETA` | 啟用多容器 sidecar 功能 |

**Template 層級** (`spec.template.metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/cpu-throttling: 'false'` | CPU 持續分配，確保 OTel Collector 背景運行 |
| `run.googleapis.com/container-dependencies` | 定義容器啟動順序，`{app:[collector]}` 表示 app 依賴 collector |
| `run.googleapis.com/secrets` | 掛載 Secret Manager 密鑰 |
| `run.googleapis.com/execution-environment: gen2` | 使用第二代執行環境 |
| `run.googleapis.com/startup-cpu-boost` | 啟動時提供額外 CPU |
| `autoscaling.knative.dev/maxScale` | 最大實例數 |

### Health Probes 說明

**App 容器** (Spring Boot Actuator):

| Probe | 路徑 | 用途 |
|-------|------|------|
| `startupProbe` | `/actuator/health/readiness` | 判斷容器是否啟動完成 |
| `livenessProbe` | `/actuator/health/liveness` | 判斷容器是否正常運行 |

**Collector 容器** (OTel health_check extension):

| Probe | 路徑 | 用途 |
|-------|------|------|
| `startupProbe` | `/` (port 13133) | 判斷 Collector 是否啟動完成 |
| `livenessProbe` | `/` (port 13133) | 判斷 Collector 是否正常運行 |

```bash
cat > cloudrun-service.yaml << EOF
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: $SERVICE_NAME
  annotations:
    run.googleapis.com/launch-stage: BETA
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/cpu-throttling: 'false'
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${OTEL_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${OTEL_SECRET_NAME},${CONFIG_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${CONFIG_SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
    spec:
      containerConcurrency: $CONTAINER_CONCURRENCY
      timeoutSeconds: 300
      serviceAccountName: $SERVICE_ACCOUNT
      containers:
        - name: app
          image: $APP_IMAGE
          ports:
            - name: http1
              containerPort: $APP_PORT
          env:
            - name: spring.profiles.active
              value: "gcp,$ENV_PROFILE"
            - name: spring.config.additional-location
              value: "optional:file:/config/"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://localhost:4318"
          resources:
            limits:
              cpu: $APP_CPU
              memory: $APP_MEMORY
          startupProbe:
            httpGet:
              path: /actuator/health/readiness
              port: $APP_PORT
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 30
            timeoutSeconds: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: $APP_PORT
            periodSeconds: 30
            failureThreshold: 3
            timeoutSeconds: 3
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true
        - name: collector
          image: $OTEL_COLLECTOR_IMAGE
          args:
            - --config=/etc/otelcol-google/config.yaml
          resources:
            limits:
              cpu: $OTEL_CPU
              memory: $OTEL_MEMORY
          startupProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 12
          livenessProbe:
            httpGet:
              path: /
              port: 13133
            periodSeconds: 30
            timeoutSeconds: 30
          volumeMounts:
            - name: otel-config
              mountPath: /etc/otelcol-google/
              readOnly: true
      volumes:
        - name: app-config
          secret:
            secretName: $CONFIG_SECRET_NAME
            items:
              - key: latest
                path: application-${ENV_PROFILE}.yaml
        - name: otel-config
          secret:
            secretName: $OTEL_SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF

echo "✅ 已建立 cloudrun-service.yaml"
```

---

## Step 9: 部署 Cloud Run 服務

使用 `gcloud run services replace` 部署或更新服務：

```bash
gcloud run services replace cloudrun-service.yaml \
  --region=$REGION \
  --project=$PROJECT_ID
```

---

## Step 10: 設定存取權限

Ledger 服務通常不需要公開存取，只接收 Pub/Sub 推送的訊息。

### 選項 A: 僅允許 Pub/Sub 推送 (建議)

```bash
# 不需要設定 allUsers，Pub/Sub 使用 service account 身分驗證
echo "✅ Ledger 服務設定為內部存取，透過 Pub/Sub 接收事件"
```

### 選項 B: 允許公開存取 (測試用)

```bash
gcloud run services add-iam-policy-binding $SERVICE_NAME \
  --region=$REGION \
  --member="allUsers" \
  --role="roles/run.invoker"
```

---

## Step 11: 驗證部署

### 11.1 取得服務 URL

```bash
# Deterministic URL (與 Console 顯示一致)
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
SERVICE_URL="https://${SERVICE_NAME}-${PROJECT_NUMBER}.${REGION}.run.app"
echo "Service URL: $SERVICE_URL"
```

### 11.2 健康檢查

```bash
# 如果設定了公開存取
curl -s "${SERVICE_URL}/actuator/health" | jq .

# 如果未設定公開存取，使用 gcloud 代理
gcloud run services proxy $SERVICE_NAME --region=$REGION &
sleep 3
curl -s "http://localhost:8080/actuator/health" | jq .
```

### 11.3 查看日誌

```bash
gcloud run services logs read $SERVICE_NAME --region=$REGION --limit=50
```

---

## 快速部署 (一鍵執行)

將 Step 1 的變數設定好後，執行以下完整腳本：

```bash
# 確保已設定所有變數後執行
cd ~/cloudrun-deploy && \

# ========== Step 6: 建立配置檔 ==========
# OTel Collector 配置
cat > otel-collector-config.yaml << EOF
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
processors:
  batch:
    send_batch_size: 200
    timeout: 5s
  memory_limiter:
    check_interval: 1s
    limit_percentage: 65
    spike_limit_percentage: 20
  resourcedetection:
    detectors: [env, gcp]
    timeout: 2s
    override: false
exporters:
  googlecloud:
    log:
      default_log_name: $OTEL_LOG_NAME
  googlemanagedprometheus:
extensions:
  health_check:
    endpoint: 0.0.0.0:13133
service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlemanagedprometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
EOF

# 應用程式配置
cat > application-${ENV_PROFILE}.yaml << 'APPEOF'
ledger-mongodb-uri: ${sm@ledger-mongodb-uri}

management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus

logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
APPEOF

# ========== Step 7: 建立 Secrets 並授權 ==========
# OTel Collector 配置 Secret
gcloud secrets describe $OTEL_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID || \
  gcloud secrets create $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 授權 Service Account 存取 OTel 配置
gcloud secrets add-iam-policy-binding $OTEL_SECRET_NAME \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="roles/secretmanager.secretAccessor" \
  --project=$PROJECT_ID --quiet

# 應用程式配置 Secret
gcloud secrets describe $CONFIG_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --project=$PROJECT_ID || \
  gcloud secrets create $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 授權 Service Account 存取應用程式配置
gcloud secrets add-iam-policy-binding $CONFIG_SECRET_NAME \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="roles/secretmanager.secretAccessor" \
  --project=$PROJECT_ID --quiet

# ========== Step 8: 建立 Cloud Run 服務 YAML ==========
cat > cloudrun-service.yaml << EOF
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: $SERVICE_NAME
  annotations:
    run.googleapis.com/launch-stage: BETA
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/cpu-throttling: 'false'
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${OTEL_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${OTEL_SECRET_NAME},${CONFIG_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${CONFIG_SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
    spec:
      containerConcurrency: $CONTAINER_CONCURRENCY
      timeoutSeconds: 300
      serviceAccountName: $SERVICE_ACCOUNT
      containers:
        - name: app
          image: $APP_IMAGE
          ports:
            - name: http1
              containerPort: $APP_PORT
          env:
            - name: spring.profiles.active
              value: "gcp,$ENV_PROFILE"
            - name: spring.config.additional-location
              value: "optional:file:/config/"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://localhost:4318"
          resources:
            limits:
              cpu: $APP_CPU
              memory: $APP_MEMORY
          startupProbe:
            httpGet:
              path: /actuator/health/readiness
              port: $APP_PORT
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 30
            timeoutSeconds: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: $APP_PORT
            periodSeconds: 30
            failureThreshold: 3
            timeoutSeconds: 3
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true
        - name: collector
          image: $OTEL_COLLECTOR_IMAGE
          args:
            - --config=/etc/otelcol-google/config.yaml
          resources:
            limits:
              cpu: $OTEL_CPU
              memory: $OTEL_MEMORY
          startupProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 12
          livenessProbe:
            httpGet:
              path: /
              port: 13133
            periodSeconds: 30
            timeoutSeconds: 30
          volumeMounts:
            - name: otel-config
              mountPath: /etc/otelcol-google/
              readOnly: true
      volumes:
        - name: app-config
          secret:
            secretName: $CONFIG_SECRET_NAME
            items:
              - key: latest
                path: application-${ENV_PROFILE}.yaml
        - name: otel-config
          secret:
            secretName: $OTEL_SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF

# ========== Step 9: 部署 ==========
gcloud run services replace cloudrun-service.yaml --region=$REGION --project=$PROJECT_ID

echo "✅ 部署完成: $(gcloud run services describe $SERVICE_NAME --region=$REGION --format='value(status.url)')"
```

---

## 常用指令

### Cloud Run 服務管理

```bash
# 查看日誌
gcloud run services logs read $SERVICE_NAME --region=$REGION --limit=50

# 查看服務狀態
gcloud run services describe $SERVICE_NAME --region=$REGION

# 更新映像版本
export APP_IMAGE="docker.io/spike19820318/ledger:0.0.3"
# 然後重新執行 Step 8 (Cloud Run YAML) 和 Step 9 (部署)

# 刪除服務
gcloud run services delete $SERVICE_NAME --region=$REGION --quiet
```

### Secret 管理

```bash
# 更新應用程式配置
gcloud secrets versions add $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --project=$PROJECT_ID

# 更新 OTel Collector 配置
gcloud secrets versions add $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID

# 查看 Secret 版本
gcloud secrets versions list $CONFIG_SECRET_NAME --project=$PROJECT_ID
```

---

## 觀測連結

```bash
echo "Cloud Trace:      https://console.cloud.google.com/traces/list?project=$PROJECT_ID"
echo "Cloud Monitoring: https://console.cloud.google.com/monitoring?project=$PROJECT_ID"
echo "Cloud Logging:    https://console.cloud.google.com/logs?project=$PROJECT_ID"
```

---

## 常見問題排除

### 問題 1: Secret Manager 權限不足

**錯誤訊息：**
```
Permission denied on secret: projects/PROJECT_ID/secrets/ledger-config/versions/latest
```

**原因：** Service Account 沒有存取 Secret 的權限。

**解決方案：**
```bash
# 授權存取所有必要的 secrets
for SECRET_NAME in "ledger-mongodb-uri" "ledger-config" "otel-collector-config"
do
  gcloud secrets add-iam-policy-binding $SECRET_NAME \
    --member="serviceAccount:$SERVICE_ACCOUNT" \
    --role="roles/secretmanager.secretAccessor" \
    --project=$PROJECT_ID
done
```

---

### 問題 2: Pub/Sub 權限不足

**錯誤訊息：**
```
PERMISSION_DENIED: User not authorized to perform this action.
at com.google.cloud.pubsub.v1.TopicAdminClient.getTopic
```

**原因：** Service Account 沒有 Pub/Sub 權限，無法檢查 Topic 是否存在。

**解決方案：**
```bash
# 檢查目前 Pub/Sub 權限
gcloud projects get-iam-policy $PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:$SERVICE_ACCOUNT AND bindings.role~pubsub" \
  --format="table(bindings.role)"

# 授予 pubsub.editor 權限
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="roles/pubsub.editor" \
  --condition=None \
  --quiet
```

---

### 問題 3: Pub/Sub Subscription 已存在

**錯誤訊息：**
```
ALREADY_EXISTS: Resource already exists in the project (resource=llm-gateway-usage.ledger)
```

**原因：** Subscription 已存在（可能由其他服務或手動建立），Spring Cloud Stream 嘗試重新建立時發生衝突。

**解決方案：**
```bash
# 查看現有 subscription
gcloud pubsub subscriptions describe llm-gateway-usage.ledger --project=$PROJECT_ID

# 刪除後讓應用程式自動重建
gcloud pubsub subscriptions delete llm-gateway-usage.ledger --project=$PROJECT_ID --quiet

# 觸發 Cloud Run 重新部署
gcloud run services update $SERVICE_NAME --region=$REGION --project=$PROJECT_ID \
  --update-labels="redeploy=$(date +%s)"
```

---

### 問題 4: Docker Image 找不到

**錯誤訊息：**
```
Image not found: mirror.gcr.io/spike19820318/ledger:0.0.3
```

**原因：** Cloud Run 預設從 GCR mirror 拉取映像，Docker Hub 映像需要加上 `docker.io/` 前綴。

**解決方案：**
```bash
# 使用完整的 Docker Hub 路徑
export APP_IMAGE="docker.io/spike19820318/ledger:0.0.2"
```

---

### 問題 5: Firestore MongoDB 寫入權限不足

**錯誤訊息：**
```
WriteError{code=13, message='Missing or insufficient permissions.'}
MongoWriteException: Write operation error on server
```

**原因：** MongoDB 資料庫使用者 `ledger-user` 的角色權限不足，無法寫入資料。

**解決方案：**

透過 [Google Cloud Console](https://console.cloud.google.com/firestore/databases) 修改使用者角色：

1. 前往 **Firestore > 資料庫**
2. 選擇資料庫 `ledger-db`
3. 點擊 **Auth** (使用者驗證)
4. 找到使用者 `ledger-user`，點擊右側選單
5. 將角色從「使用者」或「檢視者」**切換為「擁有者」**

**角色說明：**

| 角色 | 權限 | 說明 |
|------|------|------|
| 檢視者 (Viewer) | 唯讀 | 僅能讀取資料 |
| 使用者 (User) | 讀寫 | 讀取和寫入資料 |
| 擁有者 (Owner) | 完整存取 | 所有權限，包含管理操作 |

> **注意**：Firestore 快取 IAM 權限 5 分鐘，角色變更後最多需等待 5 分鐘才會生效。

**生產環境建議：** 使用「使用者 (User)」角色即可滿足讀寫需求，「擁有者」權限過大。

---

### 問題 6: 應用程式啟動警告

**警告訊息：**
```
WARN BeanFactoryAwareFunctionRegistry : Failed to locate function 'usageEventConsumer'
WARN JvmGcMetrics : GC notifications will not be available
```

**說明：** 這些是 AOT/Native Image 模式下的正常警告，不影響功能：
- `Failed to locate function` - AOT 模式下的反射限制，但 channel 會正常綁定
- `GC notifications will not be available` - Native Image 不支援 JMX GC 監控

**驗證方式：**
```bash
# 確認 channel 有 subscriber
# 日誌應顯示: Channel 'ledger.usageEventConsumer-in-0' has 1 subscriber(s).

# 健康檢查
curl -s "${SERVICE_URL}/actuator/health" | jq .
```

---

## 參考資料

### Google Cloud 官方文件
- [Cloud Run Secrets (掛載 Secret 為檔案)](https://docs.cloud.google.com/run/docs/configuring/services/secrets)
- [OpenTelemetry Collector for Cloud Run](https://docs.cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run)
- [Google-built OTel Collector](https://docs.cloud.google.com/stackdriver/docs/instrumentation/google-built-otel)
- [Cloud Run Multi-Container (Sidecar)](https://docs.cloud.google.com/run/docs/deploying#sidecars)
- [Cloud Run Logging](https://docs.cloud.google.com/run/docs/logging)

### Spring Boot 配置
- [Spring Boot External Config](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Spring Cloud GCP Secret Manager](https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#secret-manager)

### OpenTelemetry 官方資源
- [OTel Collector Releases](https://github.com/GoogleCloudPlatform/opentelemetry-operations-collector/releases)
- [Google Cloud Exporter](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/googlecloudexporter/README.md)

### 相關文件
- [Firestore MongoDB 相容資料庫設定指南](./FIRESTORE-MONGODB-SETUP.md)
