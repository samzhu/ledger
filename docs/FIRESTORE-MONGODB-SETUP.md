# Firestore MongoDB 相容資料庫設定指南

本文件說明如何使用 gcloud CLI 建立 Firestore MongoDB 相容資料庫，並將連線字串安全地儲存至 Secret Manager。

## 目錄

- [前置需求](#前置需求)
- [步驟一：建立 Firestore 資料庫](#步驟一建立-firestore-資料庫)
- [步驟二：建立使用者憑證](#步驟二建立使用者憑證)
  - [2.1 設定使用者角色（重要）](#21-設定使用者角色重要)
- [步驟三：取得連線資訊](#步驟三取得連線資訊)
- [步驟四：組合連線字串](#步驟四組合連線字串)
- [步驟五：儲存至 Secret Manager](#步驟五儲存至-secret-manager)
- [步驟六：設定 Cloud Run Service Account 權限](#步驟六設定-cloud-run-service-account-權限)
- [應用程式整合](#應用程式整合)
- [連線參數說明](#連線參數說明)
- [參考資源](#參考資源)

---

## 前置需求

- 已安裝 [Google Cloud SDK](https://cloud.google.com/sdk/docs/install)
- 已建立 GCP 專案並啟用計費
- 具有專案的 Owner 或 Editor 權限

---

## 步驟一：建立 Firestore 資料庫

### 1.1 設定專案

```bash
gcloud config set project YOUR_PROJECT_ID
```

### 1.2 建立 Enterprise Edition 資料庫

```bash
gcloud firestore databases create \
    --database=DATABASE_ID \
    --location=LOCATION \
    --edition=enterprise
```

**參數說明：**

| 參數 | 說明 | 範例 |
|------|------|------|
| `--database` | 資料庫 ID | `ledger-db` |
| `--location` | 資料庫區域 | `asia-east1` (台灣) |
| `--edition` | 版本，MongoDB 相容需使用 `enterprise` | `enterprise` |

**可用區域：**

| 區域 | 位置 |
|------|------|
| `asia-east1` | 台灣 |
| `asia-northeast1` | 東京 |
| `asia-southeast1` | 新加坡 |
| `us-central1` | 美國中部 |
| `europe-west1` | 比利時 |

**範例：**

```bash
gcloud firestore databases create \
    --database=ledger-db \
    --location=asia-east1 \
    --edition=enterprise
```

**成功輸出：**

```yaml
response:
  '@type': type.googleapis.com/google.firestore.admin.v1.Database
  databaseEdition: ENTERPRISE
  locationId: asia-east1
  mongodbCompatibleDataAccessMode: DATA_ACCESS_MODE_ENABLED
  name: projects/YOUR_PROJECT/databases/ledger-db
  uid: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

---

## 步驟二：建立使用者憑證

使用 SCRAM-SHA-256 認證需要建立使用者憑證：

```bash
gcloud firestore user-creds create USERNAME --database=DATABASE_ID
```

**範例：**

```bash
gcloud firestore user-creds create ledger-user --database=ledger-db
```

**成功輸出：**

```yaml
name: projects/YOUR_PROJECT/databases/ledger-db/userCreds/ledger-user
securePassword: GENERATED_PASSWORD
state: ENABLED
```

> **重要：** `securePassword` 只會顯示一次，請立即複製並安全保存！

### 2.1 設定使用者角色（重要）

建立使用者後，需透過 [Google Cloud Console](https://console.cloud.google.com/firestore/databases) 設定適當的角色：

1. 前往 **Firestore > 資料庫**
2. 選擇資料庫 `ledger-db`
3. 點擊 **Auth** (使用者驗證)
4. 找到使用者 `ledger-user`，點擊右側選單
5. 選擇適當的角色

**可用角色：**

| 角色 | 權限 | 適用情境 |
|------|------|----------|
| 檢視者 (Viewer) | 唯讀 | 僅讀取資料的應用程式 |
| 使用者 (User) | 讀寫 | **一般應用程式建議使用** |
| 擁有者 (Owner) | 完整存取 | 需要管理操作的場景（權限較大） |

> **建議：**
> - **生產環境**：使用「使用者 (User)」角色，符合最小權限原則
> - **開發/測試**：可使用「擁有者 (Owner)」方便除錯
> - **唯讀服務**：使用「檢視者 (Viewer)」

> **注意：**
> - Firestore 快取 IAM 權限 5 分鐘，角色變更後最多需等待 5 分鐘生效
> - 若未設定角色或設為 Viewer，寫入操作會出現 `WriteError{code=13, message='Missing or insufficient permissions'}`

---

## 步驟三：取得連線資訊

取得資料庫的 UID 和 Location：

```bash
gcloud firestore databases describe --database=DATABASE_ID --format='yaml(locationId, uid)'
```

**範例：**

```bash
gcloud firestore databases describe --database=ledger-db --format='yaml(locationId, uid)'
```

**輸出：**

```yaml
locationId: asia-east1
uid: 5ccf53f3-70b0-4cde-8211-6ee3e483cd8d
```

---

## 步驟四：組合連線字串

### 連線字串格式

```
mongodb://USERNAME:PASSWORD@UID.LOCATION.firestore.goog:443/DATABASE_ID?loadBalanced=true&authMechanism=SCRAM-SHA-256&tls=true&retryWrites=false
```

### 替換變數

| 變數 | 說明 | 來源 |
|------|------|------|
| `USERNAME` | 使用者名稱 | 步驟二建立的使用者 |
| `PASSWORD` | 使用者密碼 | 步驟二輸出的 `securePassword` |
| `UID` | 資料庫 UUID | 步驟三取得 |
| `LOCATION` | 資料庫區域 | 步驟三取得 |
| `DATABASE_ID` | 資料庫 ID | 步驟一指定 |

### 範例連線字串

```
mongodb://ledger-user:YOUR_PASSWORD@5ccf53f3-70b0-4cde-8211-6ee3e483cd8d.asia-east1.firestore.goog:443/ledger-db?loadBalanced=true&authMechanism=SCRAM-SHA-256&tls=true&retryWrites=false
```

### 測試連線

使用 `mongosh` 測試連線：

```bash
mongosh 'mongodb://USERNAME:PASSWORD@UID.LOCATION.firestore.goog:443/DATABASE_ID?loadBalanced=true&authMechanism=SCRAM-SHA-256&tls=true&retryWrites=false'
```

---

## 步驟五：儲存至 Secret Manager

### 5.1 啟用 Secret Manager API

```bash
gcloud services enable secretmanager.googleapis.com
```

### 5.2 建立 Secret

```bash
echo -n 'YOUR_MONGODB_URI' | gcloud secrets create SECRET_NAME \
    --replication-policy="automatic" \
    --data-file=-
```

**範例：**

```bash
echo -n 'mongodb://ledger-user:YOUR_PASSWORD@UID.asia-east1.firestore.goog:443/ledger-db?loadBalanced=true&authMechanism=SCRAM-SHA-256&tls=true&retryWrites=false' | gcloud secrets create ledger-mongodb-uri \
    --replication-policy="automatic" \
    --data-file=-
```

### 5.3 驗證 Secret

```bash
# 查看 Secret 資訊
gcloud secrets describe ledger-mongodb-uri

# 讀取 Secret 值（測試用）
gcloud secrets versions access latest --secret="ledger-mongodb-uri"
```

### 5.4 更新 Secret（如需要）

```bash
echo -n 'NEW_MONGODB_URI' | gcloud secrets versions add ledger-mongodb-uri --data-file=-
```

---

## 步驟六：設定 Cloud Run Service Account 權限

Cloud Run 服務需要專用的 Service Account 來存取 Secret Manager、Firestore 和可觀測性服務。

建議建立專用 Service Account `ledger-sa`，遵循最小權限原則。

### 6.1 必要的 IAM 角色

| 角色 | 用途 | 說明 |
|------|------|------|
| `roles/secretmanager.secretAccessor` | 讀取 Secret Manager | 允許讀取 secret 內容 |
| `roles/datastore.user` | 讀寫 Firestore | 允許對 Firestore 進行 CRUD 操作 |
| `roles/logging.logWriter` | 寫入 Cloud Logging | 允許寫入日誌 |
| `roles/monitoring.metricWriter` | 寫入 Cloud Monitoring | 允許寫入指標 |
| `roles/cloudtrace.agent` | 寫入 Cloud Trace | 允許寫入追蹤資料 |

### 6.2 建立專用 Service Account 並授予權限

```bash
# 設定變數
export PROJECT_ID="YOUR_PROJECT_ID"
export DATABASE_ID="ledger-db"
export SA_NAME="ledger-sa"
export SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

# 建立 Service Account
gcloud iam service-accounts create $SA_NAME \
    --display-name="Ledger Cloud Run Service Account" \
    --description="Service account for Ledger application running on Cloud Run"

# 授予 Secret Manager 存取權限
gcloud secrets add-iam-policy-binding ledger-mongodb-uri \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/secretmanager.secretAccessor"

# 授予 Firestore 讀寫權限 (選擇以下其中一種方式)

# 方式 A: 開發環境 - 可存取所有資料庫
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/datastore.user" \
    --condition=None

# 方式 B: 生產環境 - 限制只能存取特定資料庫 (建議)
# gcloud projects add-iam-policy-binding $PROJECT_ID \
#     --member="serviceAccount:${SA_EMAIL}" \
#     --role="roles/datastore.user" \
#     --condition="expression=resource.name == \"projects/${PROJECT_ID}/databases/${DATABASE_ID}\",title=${DATABASE_ID}-access"

# 授予可觀測性權限
for ROLE in \
    "roles/logging.logWriter" \
    "roles/monitoring.metricWriter" \
    "roles/cloudtrace.agent"
do
    gcloud projects add-iam-policy-binding $PROJECT_ID \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="$ROLE" \
        --condition=None
done
```

> **生產環境建議**：使用 IAM Condition 限制 Service Account 只能存取特定資料庫，遵循最小權限原則。
>
> **注意**：Firestore IAM 權限快取時間為 5 分鐘，角色變更最多需要 5 分鐘才會生效。

### 6.4 驗證權限設定

```bash
# 檢查 Secret Manager IAM 綁定
gcloud secrets get-iam-policy ledger-mongodb-uri

# 檢查專案層級 IAM 綁定
gcloud projects get-iam-policy $PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.role:roles/datastore.user" \
    --format="table(bindings.members)"
```

### 6.5 IAM 角色權限詳解

#### `roles/secretmanager.secretAccessor`

| 權限 | 說明 |
|------|------|
| `secretmanager.versions.access` | 讀取 secret 版本內容 |

#### `roles/datastore.user`

| 權限 | 說明 |
|------|------|
| `datastore.entities.create` | 建立文件 |
| `datastore.entities.get` | 讀取文件 |
| `datastore.entities.list` | 列出文件 |
| `datastore.entities.update` | 更新文件 |
| `datastore.entities.delete` | 刪除文件 |
| `datastore.databases.get` | 取得資料庫資訊 |

> **注意：** Firestore 的 IAM 權限快取時間為 5 分鐘，角色變更最多需要 5 分鐘才會生效。

---

## 應用程式整合

### Cloud Run 環境變數

部署時將 Secret 掛載為環境變數：

```bash
gcloud run deploy SERVICE_NAME \
    --image=IMAGE_URL \
    --set-secrets="MONGODB_URI=ledger-mongodb-uri:latest"
```

### Spring Boot 整合（本專案配置方式）

本專案使用 `application-lab.yaml` 配置，透過 `${sm@secret-name}` 語法引用 Secret：

```yaml
# config/application-lab.yaml
ledger-mongodb-uri: ${sm@ledger-mongodb-uri}
```

#### 1. 加入依賴

```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>spring-cloud-gcp-starter-secretmanager</artifactId>
</dependency>
```

#### 2. 授權 Service Account

```bash
gcloud secrets add-iam-policy-binding ledger-mongodb-uri \
    --member="serviceAccount:SERVICE_ACCOUNT_EMAIL" \
    --role="roles/secretmanager.secretAccessor"
```

### 直接使用環境變數（替代方式）

```yaml
spring:
  data:
    mongodb:
      uri: ${MONGODB_URI}
```

---

## 連線參數說明

| 參數 | 值 | 必要性 | 說明 |
|------|-----|--------|------|
| `loadBalanced` | `true` | **必要** | Firestore 為無伺服器架構，驅動程式需使用負載平衡模式，不探測 replica set 拓撲 |
| `tls` | `true` | **必要** | 強制使用 TLS/SSL 加密通訊，最低 128-bit 金鑰長度 |
| `retryWrites` | `false` | **必要** | Firestore MongoDB 相容層不支援 retryable writes（retryReads 是支援的） |
| `authMechanism` | `SCRAM-SHA-256` | SCRAM 認證時必要 | 基於 IETF RFC 5802 標準的認證機制，密碼不以明文傳輸 |

> **注意：** 以上參數均為 Google Cloud 官方建議設定，缺少任何必要參數都可能導致連線失敗。

---

## 完整指令摘要

```bash
# 1. 設定專案
export PROJECT_ID="YOUR_PROJECT_ID"
gcloud config set project $PROJECT_ID

# 2. 建立 Firestore Enterprise 資料庫
gcloud firestore databases create \
    --database=ledger-db \
    --location=asia-east1 \
    --edition=enterprise

# 3. 建立使用者憑證（記下 securePassword）
gcloud firestore user-creds create ledger-user --database=ledger-db

# 4. 取得連線資訊
gcloud firestore databases describe --database=ledger-db --format='yaml(locationId, uid)'

# 5. 啟用 Secret Manager
gcloud services enable secretmanager.googleapis.com

# 6. 儲存連線字串到 Secret Manager（替換實際值）
echo -n 'mongodb://USERNAME:PASSWORD@UID.LOCATION.firestore.goog:443/DATABASE_ID?loadBalanced=true&authMechanism=SCRAM-SHA-256&tls=true&retryWrites=false' | gcloud secrets create ledger-mongodb-uri --replication-policy="automatic" --data-file=-

# 7. 設定 Cloud Run Service Account 權限
export DATABASE_ID="ledger-db"
export SA_NAME="ledger-sa"
export SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

# 7a. 建立 Service Account
gcloud iam service-accounts create $SA_NAME \
    --display-name="Ledger Cloud Run Service Account" \
    --description="Service account for Ledger application running on Cloud Run"

# 7b. 授予 Secret Manager 存取權限
gcloud secrets add-iam-policy-binding ledger-mongodb-uri \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/secretmanager.secretAccessor"

# 7c. 授予 Firestore 讀寫權限
# 開發環境: --condition=None (可存取所有資料庫)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/datastore.user" \
    --condition=None

# 生產環境建議: 限制只能存取特定資料庫
# gcloud projects add-iam-policy-binding $PROJECT_ID \
#     --member="serviceAccount:${SA_EMAIL}" \
#     --role="roles/datastore.user" \
#     --condition="expression=resource.name == \"projects/${PROJECT_ID}/databases/${DATABASE_ID}\",title=${DATABASE_ID}-access"

# 7d. 授予可觀測性權限
for ROLE in "roles/logging.logWriter" "roles/monitoring.metricWriter" "roles/cloudtrace.agent"; do
    gcloud projects add-iam-policy-binding $PROJECT_ID \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="$ROLE" \
        --condition=None
done
```

---

## 參考資源

### Firestore MongoDB 相容性
- [Firestore with MongoDB compatibility overview | Google Cloud](https://cloud.google.com/firestore/mongodb-compatibility/docs/overview)
- [Authenticate and connect to a database | Firestore](https://firebase.google.com/docs/firestore/enterprise/connect)
- [Create and manage databases | Firestore](https://firebase.google.com/docs/firestore/enterprise/create-databases)
- [Identity and Access Management (IAM) | Firestore](https://cloud.google.com/firestore/mongodb-compatibility/docs/security/iam)

### Secret Manager
- [Secret Manager Documentation | Google Cloud](https://cloud.google.com/secret-manager/docs)
- [Configure secrets for services | Cloud Run](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Access control with IAM | Secret Manager](https://cloud.google.com/secret-manager/docs/access-control)

### Cloud Run & IAM
- [Introduction to service identity | Cloud Run](https://cloud.google.com/run/docs/securing/service-identity)
- [Cloud Run IAM roles | Google Cloud](https://cloud.google.com/run/docs/reference/iam/roles)

### gcloud CLI
- [gcloud firestore | Google Cloud SDK](https://cloud.google.com/sdk/gcloud/reference/firestore)
- [gcloud secrets | Google Cloud SDK](https://cloud.google.com/sdk/gcloud/reference/secrets)
