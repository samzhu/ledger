package io.github.samzhu.ledger.config;

import org.springframework.boot.info.GitProperties;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局 Controller Advice，注入 Git 資訊到所有視圖。
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final GitProperties gitProperties;

    public GlobalModelAdvice(@Nullable GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    /**
     * 應用版本：優先 tag，否則 commit id。
     */
    @ModelAttribute("appVersion")
    public String appVersion() {
        if (gitProperties == null) {
            return "dev";
        }
        String tag = gitProperties.get("closest.tag.name");
        if (tag != null && !tag.isBlank()) {
            return tag;
        }
        String commitId = gitProperties.getShortCommitId();
        return commitId != null ? commitId : "dev";
    }

    /**
     * Git commit ID 縮寫。
     */
    @ModelAttribute("gitCommitId")
    public String gitCommitId() {
        return gitProperties != null ? gitProperties.getShortCommitId() : null;
    }

    /**
     * Git branch 名稱。
     */
    @ModelAttribute("gitBranch")
    public String gitBranch() {
        return gitProperties != null ? gitProperties.getBranch() : null;
    }
}
