package io.jenkins.plugins.awsinspectorbuildstep.models.sbom.Components;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Tool {
    private String name;
    private String vendor;
    private String version;
}
