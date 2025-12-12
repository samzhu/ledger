# Ledger GitHub CI 規劃

## 文件資訊
- **建立日期**: 2025-12-11
- **目的**: 規劃 Ledger 專案的 GitHub Actions CI/CD 流程
- **參考**: Gate 專案的 `.github/workflows/release.yml`

---

## 一、CI/CD 目標

### 1.1 建置目標

1. **Native Image**: 使用 GraalVM 建置 Spring Boot Native Image
2. **Docker Image**: 推送到 Docker Hub Registry
3. **觸發方式**:
   - Git Tag 推送 (格式: `1.0.0`)
   - 手動觸發 (workflow_dispatch)

### 1.2 參考 Gate 專案的成功經驗

Gate 專案的 CI 配置重點:
- 使用 Gradle `bootBuildImage` 建置 Native Image
- 透過 `--publishImage` 直接推送到 Docker Hub
- 使用 `SPRING_PROFILES_ACTIVE=aot` 確保 AOT 編譯正確
- 使用 GitHub Actions 原生工具 (setup-java, setup-gradle)

### 1.3 與 Gate 的差異

| 項目 | Gate | Ledger | 調整原因 |
|------|------|--------|----------|
| **AOT Profile** | 需要 (`aot`) | **需要 (`aot`)** | 部署於 GCP Cloud Run,需要 Pub/Sub 和 Secret Manager |
| **Image Name** | `gate` | `ledger` | 專案名稱不同 |
| **Java Version** | 25 | 25 | 一致 |
| **Spring Boot** | 4.0.0 | 3.5.8 | 版本不同但流程相同 |

---

## 二、GitHub Actions Workflow 設計

### 2.1 Workflow 檔案位置

```
.github/
└── workflows/
    └── release.yml
```

### 2.2 觸發條件

```yaml
on:
  push:
    tags:
      - '[0-9]+.[0-9]+.[0-9]+*'  # 1.0.0, 1.0.1, 1.0.0-rc1 等
  workflow_dispatch:
    inputs:
      version:
        description: 'Image version tag (e.g., 1.0.0)'
        required: true
        default: 'latest'
```

**設計說明**:
- Tag 推送: 符合 Semantic Versioning (1.0.0, 2.1.3)
- 手動觸發: 允許指定版本號或使用 `latest`

### 2.3 環境變數

```yaml
env:
  DOCKER_USERNAME: ${{ vars.DOCKER_USERNAME }}
  IMAGE_NAME: ledger
  VERSION: ${{ github.event.inputs.version || github.ref_name }}
```

**說明**:
- `DOCKER_USERNAME`: 從 GitHub Repository Variables 讀取
- `IMAGE_NAME`: 固定為 `ledger`
- `VERSION`: 優先使用手動輸入,否則使用 git tag

### 2.4 權限設定

```yaml
permissions:
  contents: read  # 只需要讀取程式碼
```

**最小權限原則**: 僅授予必要的權限。

---

## 三、完整 Workflow 內容

### 3.1 release.yml

```yaml
name: Build and Push Native Docker Image

on:
  push:
    tags:
      - '[0-9]+.[0-9]+.[0-9]+*'
  workflow_dispatch:
    inputs:
      version:
        description: 'Image version tag (e.g., 1.0.0)'
        required: true
        default: 'latest'

permissions:
  contents: read

env:
  DOCKER_USERNAME: ${{ vars.DOCKER_USERNAME }}
  IMAGE_NAME: ledger
  VERSION: ${{ github.event.inputs.version || github.ref_name }}

jobs:
  build-and-push:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v6

      - name: Setup JDK
        uses: actions/setup-java@v5
        with:
          distribution: 'temurin'
          java-version: '25'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v5

      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ env.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}

      - name: Build and Push Native Docker Image
        env:
          # AOT 編譯時使用 aot profile，確保 RabbitMQ 和 Pub/Sub Binder 都被正確註冊
          # 執行時 profile 由 Cloud Run 環境變數覆蓋 (gcp,lab 或 gcp,prod)
          SPRING_PROFILES_ACTIVE: aot
        run: |
          ./gradlew bootBuildImage \
            --imageName=docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:${{ env.VERSION }} \
            --publishImage

      - name: Output image info
        run: |
          echo "### Docker Image Published" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "| Item | Value |" >> $GITHUB_STEP_SUMMARY
          echo "|------|-------|" >> $GITHUB_STEP_SUMMARY
          echo "| Image | \`docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:${{ env.VERSION }}\` |" >> $GITHUB_STEP_SUMMARY
          echo "| Type | Spring Native (GraalVM) |" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "**Pull command:**" >> $GITHUB_STEP_SUMMARY
          echo "\`\`\`bash" >> $GITHUB_STEP_SUMMARY
          echo "docker pull docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:${{ env.VERSION }}" >> $GITHUB_STEP_SUMMARY
          echo "\`\`\`" >> $GITHUB_STEP_SUMMARY
```

### 3.2 與 Gate 的差異說明

| 項目 | Gate | Ledger | 說明 |
|------|------|--------|------|
| **Image Name** | `gate` | `ledger` | 專案名稱不同 |
| **AOT Profile** | `SPRING_PROFILES_ACTIVE=aot` | `SPRING_PROFILES_ACTIVE=aot` | 完全一致 |
| **Workflow 結構** | 同上 | 同上 | 完全一致 |

**關鍵設計**: Workflow 與 Gate 完全一致 (僅 IMAGE_NAME 不同)

AOT Profile 註解說明:
```yaml
env:
  # AOT 編譯時使用 aot profile，確保 RabbitMQ 和 Pub/Sub Binder 都被正確註冊
  # 執行時 profile 由 Cloud Run 環境變數覆蓋 (gcp,lab 或 gcp,prod)
  SPRING_PROFILES_ACTIVE: aot
```

**相關配置**:
- 參考 `docs/CONFIG-PLAN.md` 第 2.7 節 (`application-aot.yaml`)
- 確保 `src/main/resources/application-aot.yaml` 已正確配置

---

## 四、GitHub Repository 設定

### 4.1 Repository Variables

在 GitHub Repository Settings → Secrets and variables → Actions → Variables 新增:

| 名稱 | 值 | 說明 |
|------|-------|------|
| `DOCKER_USERNAME` | 你的 Docker Hub 使用者名稱 | 公開變數,可見於 logs |

### 4.2 Repository Secrets

在 GitHub Repository Settings → Secrets and variables → Actions → Secrets 新增:

| 名稱 | 值 | 說明 |
|------|-------|------|
| `DOCKER_PASSWORD` | 你的 Docker Hub Access Token | 機敏資訊,不可見於 logs |

**重要**: 使用 Docker Hub Access Token 而非密碼:
1. 前往 Docker Hub → Account Settings → Security → Access Tokens
2. 建立新的 Access Token (權限: Read & Write)
3. 複製 Token 並存入 GitHub Secret

---

## 五、使用方式

### 5.1 透過 Git Tag 觸發

```bash
# 建立並推送 tag
git tag 1.0.0
git push origin 1.0.0

# 結果: 建置 docker.io/{username}/ledger:1.0.0
```

### 5.2 透過 GitHub UI 手動觸發

1. 前往 GitHub Repository → Actions
2. 選擇 "Build and Push Native Docker Image" workflow
3. 點擊 "Run workflow"
4. 輸入 version (例如: `1.0.0` 或 `latest`)
5. 點擊 "Run workflow"

### 5.3 使用 GitHub CLI 觸發

```bash
# 安裝 GitHub CLI
brew install gh

# 認證
gh auth login

# 觸發 workflow
gh workflow run release.yml -f version=1.0.0
```

---

## 六、建置流程說明

### 6.1 建置步驟

```
1. Checkout code
   ↓
2. Setup JDK 25 (Temurin)
   ↓
3. Setup Gradle
   ↓
4. Login to Docker Hub
   ↓
5. Build Native Image (bootBuildImage)
   ├── AOT 編譯 (processAot)
   ├── GraalVM Native Image 建置
   └── Docker Image 打包
   ↓
6. Push to Docker Hub (--publishImage)
   ↓
7. Output build summary
```

### 6.2 bootBuildImage 行為

Gradle `bootBuildImage` task 會:
1. 執行 AOT 處理 (`processAot`)
2. 使用 Buildpacks 建置 Native Image
3. 打包成 Docker Image
4. 推送到 Registry (使用 `--publishImage`)

**Base Image**: Buildpacks 自動選擇 `paketobuildpacks/builder:base`

### 6.3 預估建置時間

- **首次建置**: 15-20 分鐘 (AOT + Native Compilation)
- **後續建置**: 10-15 分鐘 (Gradle cache)

**優化建議**:
- Gradle cache 由 `gradle/actions/setup-gradle@v5` 自動處理
- 考慮使用 GitHub Actions cache (可選)

---

## 七、驗證與測試

### 7.1 本地測試 CI 流程

在推送到 GitHub 前,可本地測試建置流程:

```bash
# 1. 測試 AOT 編譯 (使用 aot profile)
SPRING_PROFILES_ACTIVE=aot ./gradlew processAot

# 2. 測試完整建置 (不推送,使用 aot profile)
SPRING_PROFILES_ACTIVE=aot ./gradlew bootBuildImage --imageName=ledger:test

# 3. 測試執行
docker run --rm -p 8080:8080 ledger:test

# 4. 驗證健康檢查
curl http://localhost:8080/actuator/health
```

### 7.2 CI 建置驗證清單

建置成功後驗證:

- [ ] GitHub Actions workflow 成功完成
- [ ] Docker Hub 上有新的 image tag
- [ ] Image 大小合理 (預期: 100-200MB)
- [ ] Image 可正常啟動
- [ ] Actuator health endpoint 可存取

### 7.3 Image 測試指令

```bash
# 拉取 image
docker pull docker.io/{username}/ledger:1.0.0

# 執行 (本地測試)
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local,dev \
  -e MONGODB_URI=mongodb://host.docker.internal:27017/ledger \
  -e RABBITMQ_HOST=host.docker.internal \
  docker.io/{username}/ledger:1.0.0

# 健康檢查
curl http://localhost:8080/actuator/health
```

---

## 八、故障排除

### 8.1 常見錯誤與解決方案

#### 錯誤 1: PlaceholderResolutionException

**症狀**:
```
Could not resolve placeholder 'xxx' in value "${xxx}"
```

**原因**: 配置屬性缺少預設值

**解決方案**:
1. 在 `application.yaml` 新增預設值: `${property:default}`
2. 確認 `application-aot.yaml` 已正確配置 (參考 CONFIG-PLAN.md 第 2.7 節)
3. 確認 CI 建置時設定 `SPRING_PROFILES_ACTIVE=aot`

#### 錯誤 2: Docker Hub 認證失敗

**症狀**:
```
Error: Cannot perform an interactive login from a non TTY device
```

**原因**: Docker Hub credentials 未正確設定

**解決方案**:
1. 驗證 `DOCKER_USERNAME` 變數正確
2. 驗證 `DOCKER_PASSWORD` secret 正確 (使用 Access Token)
3. 重新產生 Docker Hub Access Token

#### 錯誤 3: 記憶體不足

**症狀**:
```
java.lang.OutOfMemoryError: GC overhead limit exceeded
```

**原因**: Native Image 編譯需要大量記憶體

**解決方案**:
在 `build.gradle` 新增記憶體配置:

```groovy
tasks.named('bootBuildImage') {
    environment = [
        'BP_NATIVE_IMAGE_BUILD_ARGUMENTS': '--verbose -J-Xmx8g'
    ]
}
```

---

## 九、進階配置

### 9.1 多標籤推送

若需要同時推送 `latest` 和版本號標籤:

```yaml
- name: Build and Push Native Docker Image
  run: |
    # Build with version tag
    ./gradlew bootBuildImage \
      --imageName=docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:${{ env.VERSION }} \
      --publishImage

    # Tag as latest and push (only for non-pre-release)
    if [[ "${{ env.VERSION }}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      docker tag docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:${{ env.VERSION }} \
                 docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:latest
      docker push docker.io/${{ env.DOCKER_USERNAME }}/${{ env.IMAGE_NAME }}:latest
    fi
```

### 9.2 建置快取優化

使用 GitHub Actions cache 加速建置:

```yaml
- name: Cache Gradle packages
  uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches
      ~/.gradle/wrapper
    key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    restore-keys: |
      ${{ runner.os }}-gradle-
```

**注意**: `gradle/actions/setup-gradle@v5` 已包含 cache,此步驟為可選。

### 9.3 建置通知

整合 Slack 或 Email 通知:

```yaml
- name: Notify on success
  if: success()
  run: |
    curl -X POST ${{ secrets.SLACK_WEBHOOK_URL }} \
      -H 'Content-Type: application/json' \
      -d '{"text":"Ledger ${{ env.VERSION }} 建置成功!"}'

- name: Notify on failure
  if: failure()
  run: |
    curl -X POST ${{ secrets.SLACK_WEBHOOK_URL }} \
      -H 'Content-Type: application/json' \
      -d '{"text":"Ledger ${{ env.VERSION }} 建置失敗!"}'
```

---

## 十、實施步驟

### Step 1: 準備 Docker Hub

1. [ ] 建立 Docker Hub Access Token
2. [ ] 記錄 Docker Hub Username

### Step 2: 設定 GitHub Repository

3. [ ] 新增 Repository Variable: `DOCKER_USERNAME`
4. [ ] 新增 Repository Secret: `DOCKER_PASSWORD`

### Step 3: 建立 Workflow 檔案

5. [ ] 建立 `.github/workflows/` 目錄
6. [ ] 建立 `.github/workflows/release.yml`
7. [ ] 複製本文第三節的完整內容

### Step 4: 本地測試

8. [ ] 執行 `./gradlew bootBuildImage --imageName=ledger:test`
9. [ ] 驗證 image 可正常啟動
10. [ ] 驗證 actuator health endpoint

### Step 5: 推送並測試 CI

11. [ ] Commit 並 push workflow 檔案
12. [ ] 使用 GitHub UI 手動觸發 (version: `test`)
13. [ ] 驗證建置成功
14. [ ] 驗證 Docker Hub 上有 `ledger:test` image

### Step 6: 正式發布

15. [ ] 建立並推送 git tag: `git tag 1.0.0 && git push origin 1.0.0`
16. [ ] 驗證 CI 自動觸發
17. [ ] 驗證 Docker Hub 上有 `ledger:1.0.0` image

---

## 十一、與 Gate 專案的對照

| 項目 | Gate | Ledger | 是否一致 |
|------|------|--------|----------|
| **Workflow 名稱** | Build and Push Native Docker Image | 同左 | ✅ |
| **觸發條件** | Tag + workflow_dispatch | 同左 | ✅ |
| **Java 版本** | 25 | 25 | ✅ |
| **Gradle Action** | setup-gradle@v5 | 同左 | ✅ |
| **Docker Registry** | Docker Hub | Docker Hub | ✅ |
| **Image 名稱** | gate | ledger | ❌ (專案不同) |
| **AOT Profile** | aot | aot | ✅ |
| **AOT 註解** | 有 (說明 Binder 註冊) | 同左 | ✅ |
| **Build Summary** | 有 | 同左 | ✅ |

**結論**: Workflow 與 Gate 完全一致，僅 `IMAGE_NAME` 環境變數不同。

---

## 十二、後續優化建議

### 12.1 短期優化

1. **建置時間優化**:
   - 監控首次建置時間
   - 如超過 20 分鐘,考慮調整記憶體配置

2. **多環境映像**:
   - 考慮建置 JVM 版本 (非 Native) 用於開發環境
   - 使用不同 tag 區分 (如 `1.0.0-jvm`, `1.0.0-native`)

### 12.2 長期優化

1. **Matrix 建置**:
   - 同時建置多個平台 (amd64, arm64)
   - 使用 Docker Buildx

2. **整合測試**:
   - 在 CI 中執行整合測試
   - 使用 Testcontainers

3. **安全掃描**:
   - 整合 Trivy 或 Snyk 掃描漏洞
   - 在推送前檢查 CVE

---

## 十三、參考資料

### 官方文件

- Spring Boot Gradle Plugin: https://docs.spring.io/spring-boot/gradle-plugin/
- GraalVM Native Image: https://www.graalvm.org/latest/reference-manual/native-image/
- Buildpacks: https://buildpacks.io/
- GitHub Actions: https://docs.github.com/en/actions

### Gate 專案參考

- Gate CI 配置: `/Users/samzhu/workspace/github-samzhu/gate/.github/workflows/release.yml`
- Gate 開發注意事項: `/Users/samzhu/workspace/github-samzhu/gate/docs/DEVELOPMENT-NOTES.md`

### Docker Hub

- Access Tokens: https://docs.docker.com/security/for-developers/access-tokens/
- Registry API: https://docs.docker.com/registry/spec/api/
