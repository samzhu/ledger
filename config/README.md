# Config 目錄

此目錄包含環境特定的**行為層**配置檔案。

## Profile 架構

Ledger 使用雙層 Profile 架構:

| 層級 | Profile | 位置 | 說明 |
|------|---------|------|------|
| 基礎設施層 | `local` / `gcp` | `src/main/resources/` | 基礎設施配置 (MQ, DB) |
| 行為層 | `dev` / `lab` / `prod` | `config/` | 環境行為配置 (日誌、監控) |

## 環境組合

- **本地開發**: `local,dev` (預設)
- **LAB 環境**: `gcp,lab`
- **生產環境**: `gcp,prod`

## 檔案列表

| 檔案 | 用途 | Git 狀態 |
|------|------|----------|
| `application-dev.yaml` | 本地開發行為 | 追蹤 |
| `application-lab.yaml` | LAB 環境行為 | 追蹤 |
| `application-prod.yaml` | 生產環境行為 | 追蹤 |

## 本地開發使用

Spring Boot 會自動載入 `config/` 目錄 (相對於執行目錄):

```bash
# 本地開發 (預設)
./gradlew bootRun

# 或指定 profiles
./gradlew bootRun --args='--spring.profiles.active=local,dev'
```

## GCP 部署使用

Cloud Run 部署時:
1. `application-lab.yaml` / `application-prod.yaml` 存放於 Secret Manager
2. 掛載到 `/config/application-{env}.yaml`
3. 設定環境變數:
   - `SPRING_PROFILES_ACTIVE=gcp,lab` (或 `gcp,prod`)
   - `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/`

## 未來擴展

如需新增機敏配置 (如資料庫密碼):

1. **本地開發**: 建立 `config/application-secrets.properties` (不進版控)
2. **GCP 環境**: 使用 Secret Manager + `sm@` 語法

參考 Gate 專案的機敏值處理模式。
