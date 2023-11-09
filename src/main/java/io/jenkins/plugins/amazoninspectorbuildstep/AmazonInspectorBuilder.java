package io.jenkins.plugins.amazoninspectorbuildstep;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.jenkins.plugins.amazoninspectorbuildstep.sbomgen.SbomgenRunner;
import io.jenkins.plugins.amazoninspectorbuildstep.credentials.UsernameCredentialsHelper;
import io.jenkins.plugins.amazoninspectorbuildstep.csvconversion.CsvConverter;
import io.jenkins.plugins.amazoninspectorbuildstep.html.HtmlGenerator;
import io.jenkins.plugins.amazoninspectorbuildstep.html.HtmlJarHandler;
import io.jenkins.plugins.amazoninspectorbuildstep.models.html.HtmlData;
import io.jenkins.plugins.amazoninspectorbuildstep.models.html.components.ImageMetadata;
import io.jenkins.plugins.amazoninspectorbuildstep.models.html.components.SeverityValues;
import io.jenkins.plugins.amazoninspectorbuildstep.models.sbom.Sbom;
import io.jenkins.plugins.amazoninspectorbuildstep.models.sbom.SbomData;
import io.jenkins.plugins.amazoninspectorbuildstep.requests.SdkRequests;
import io.jenkins.plugins.amazoninspectorbuildstep.sbomparsing.SbomOutputParser;
import io.jenkins.plugins.amazoninspectorbuildstep.sbomparsing.Severity;
import io.jenkins.plugins.amazoninspectorbuildstep.sbomparsing.SeverityCounts;
import io.jenkins.plugins.amazoninspectorbuildstep.utils.HtmlConversionUtils;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang.StringEscapeUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;

import static io.jenkins.plugins.amazoninspectorbuildstep.utils.InspectorRegions.BETA_REGIONS;
import static io.jenkins.plugins.amazoninspectorbuildstep.utils.Sanitizer.sanitizeFilePath;
import static io.jenkins.plugins.amazoninspectorbuildstep.utils.Sanitizer.sanitizeText;

public class AmazonInspectorBuilder extends Builder implements SimpleBuildStep {
    public static PrintStream logger;
    private final String archivePath;
    private final String iamRole;
    private final String awsRegion;
    private final String dockerUsername;
    private final String sbomgenPath;
    private final int countCritical;
    private final int countHigh;
    private final int countMedium;
    private final int countLow;
    private Job<?, ?> job;

    @DataBoundConstructor
    public AmazonInspectorBuilder(String archivePath, String iamRole, String awsRegion, String dockerUsername,
                                  String sbomgenPath, int countCritical, int countHigh, int countMedium, int countLow) {
        this.archivePath = archivePath;
        this.dockerUsername = dockerUsername;
        this.iamRole = iamRole;
        this.awsRegion = awsRegion;
        this.sbomgenPath = sbomgenPath;
        this.countCritical = countCritical;
        this.countHigh = countHigh;
        this.countMedium = countMedium;
        this.countLow = countLow;
    }

    private boolean doesBuildFail(Map<Severity, Integer> counts) {
        boolean criticalExceedsLimit = counts.get(Severity.CRITICAL) > countCritical;
        boolean highExceedsLimit = counts.get(Severity.HIGH) > countHigh;
        boolean mediumExceedsLimit = counts.get(Severity.MEDIUM) > countMedium;
        boolean lowExceedsLimit = counts.get(Severity.LOW) > countLow;
        
        return criticalExceedsLimit || highExceedsLimit || mediumExceedsLimit || lowExceedsLimit;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws IOException {
        logger = listener.getLogger();

        File outFile = new File(build.getRootDir(), "out");
        this.job = build.getParent();

        PrintStream printStream = new PrintStream(outFile, StandardCharsets.UTF_8);

        try {

            if (Jenkins.getInstanceOrNull() == null) {
                throw new RuntimeException("No Jenkins instance found");
            }

            UsernameCredentialsHelper usernameCredentialsHelper = new UsernameCredentialsHelper(job);
            String dockerPassword = usernameCredentialsHelper.getPassword(dockerUsername);
            String sbom = new SbomgenRunner(sbomgenPath, archivePath, dockerUsername, dockerPassword).run();

            JsonObject component = JsonParser.parseString(sbom).getAsJsonObject().get("metadata").getAsJsonObject()
                    .get("component").getAsJsonObject();

            String imageSha = "No Sha Found";
            for (JsonElement element : component.get("properties").getAsJsonArray()) {
                String elementName = element.getAsJsonObject().get("name").getAsString();
                if (elementName.equals("amazon:inspector:sbom_collector:image_id")) {
                    imageSha = element.getAsJsonObject().get("value").getAsString();
                }
            }

            listener.getLogger().println("Sending SBOM to Inspector for validation");
            String responseData = new SdkRequests(awsRegion, iamRole).requestSbom(sbom);

            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            SbomData sbomData = SbomData.builder().sbom(gson.fromJson(responseData, Sbom.class)).build();

            String sbomFileName = String.format("%s-%s-sbom.json", build.getParent().getDisplayName(),
                    build.getDisplayName()).replaceAll("[ #]", "");
            String sbomPath = String.format("%s/%s", build.getRootDir().getAbsolutePath(), sbomFileName);

            writeSbomDataToFile(gson.toJson(sbomData.getSbom()), sbomPath);

            CsvConverter converter = new CsvConverter(sbomData);
            String csvFileName = String.format("%s-%s.csv", build.getParent().getDisplayName(),
                    build.getDisplayName()).replaceAll("[ #]", "");
            String csvPath = String.format("%s/%s", build.getRootDir().getAbsolutePath(), csvFileName);

            logger.println("Converting SBOM Results to CSV.");
            converter.convert(csvPath);

            SbomOutputParser parser = new SbomOutputParser(sbomData);
            SeverityCounts severityCounts = parser.parseSbom();

            String sanitizedSbomPath = sanitizeFilePath("file://" + sbomPath);
            String sanitizedCsvPath = sanitizeFilePath("file://" + csvPath);
            String sanitizedImageId = null;
            String componentName = component.get("name").getAsString();

            if (componentName.endsWith(".tar")) {
                sanitizedImageId = sanitizeFilePath("file://" + componentName);
            } else {
                sanitizedImageId = sanitizeText(componentName);
            }

            String[] splitName = sanitizedImageId.split(":");
            String tag = null;
            if (splitName.length > 1) {
                tag = splitName[1];
            }

            HtmlData htmlData = HtmlData.builder()
                    .jsonFilePath(sanitizedSbomPath)
                    .csvFilePath(sanitizedCsvPath)
                    .imageMetadata(ImageMetadata.builder()
                            .id(splitName[0])
                            .tags(tag)
                            .sha(imageSha)
                            .build())
                    .severityValues(SeverityValues.builder()
                            .critical(severityCounts.getCounts().get(Severity.CRITICAL))
                            .high(severityCounts.getCounts().get(Severity.HIGH))
                            .medium(severityCounts.getCounts().get(Severity.MEDIUM))
                            .low(severityCounts.getCounts().get(Severity.LOW))
                            .build())
                    .vulnerabilities(HtmlConversionUtils.convertVulnerabilities(sbomData.getSbom().getVulnerabilities(),
                            sbomData.getSbom().getComponents()))
                    .build();

            String htmlJarPath = new File(HtmlJarHandler.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getPath();
            HtmlJarHandler htmlJarHandler = new HtmlJarHandler(htmlJarPath);
            String htmlPath = htmlJarHandler.copyHtmlToDir(build.getRootDir().getAbsolutePath());

            String html = new Gson().toJson(htmlData);
            new HtmlGenerator(htmlPath).generateNewHtml(html);

            listener.getLogger().println("CSV Output File: " + sanitizedCsvPath);
            listener.getLogger().println("SBOM Output File: " + sanitizedSbomPath);
            listener.getLogger().println("HTML Report File: " + sanitizeFilePath("file://" + htmlPath));

            boolean doesBuildPass = !doesBuildFail(severityCounts.getCounts());
            listener.getLogger().printf("Results: %s\nDoes Build Pass: %s\n",
                    severityCounts, doesBuildPass);

            if (doesBuildPass) {
                build.setResult(Result.SUCCESS);
            } else {
                build.setResult(Result.FAILURE);
            }

        } catch (Exception e) {
            listener.getLogger().println("Plugin execution ran into an error and is being aborted!");
            build.setResult(Result.ABORTED);
            listener.getLogger().println("Exception:" + e);
            e.printStackTrace(listener.getLogger());
        } finally {
            printStream.close();
        }
    }

    public static void writeSbomDataToFile(String sbomData, String outputFilePath) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFilePath))) {
            for (String line : sbomData.split("\n")) {
                writer.println(StringEscapeUtils.unescapeJava(line));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Symbol("Amazon Inspector")
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }

        private ListBoxModel getStringCredentialModels() {
            ListBoxModel items = new ListBoxModel();
            List<StringCredentials> credentials = CredentialsProvider.lookupCredentials(
                    StringCredentials.class,
                    Jenkins.getInstance(),
                    ACL.SYSTEM,
                    Collections.emptyList()
            );

            items.add("Select Credential ID", null);
            for (StringCredentials credential : credentials) {
                items.add(credential.getId(), credential.getId());
            }

            return items;
        }

        private ListBoxModel getUsernameCredentialModels() {
            ListBoxModel items = new ListBoxModel();
            List<UsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(
                    UsernamePasswordCredentials.class,
                    Jenkins.getInstance(),
                    ACL.SYSTEM,
                    Collections.emptyList()
            );

            items.add("Select Docker Username", null);
            for (UsernamePasswordCredentials credential : credentials) {
                items.add(credential.getUsername(), credential.getUsername());
            }

            return items;
        }

        public ListBoxModel doFillAccessKeyIdItems() {
            return getStringCredentialModels();
        }

        public ListBoxModel doFillSecretKeyIdItems() {
            return getStringCredentialModels();
        }

        public ListBoxModel doFillSessionTokenIdItems() {
            return getStringCredentialModels();
        }

        public ListBoxModel doFillDockerUsernameItems() {
            return getUsernameCredentialModels();
        }

        public ListBoxModel doFillAwsRegionItems() {
            ListBoxModel items = new ListBoxModel();

            items.add("Select AWS Region", null);

            for (String region : BETA_REGIONS) {
                items.add(region, region);
            }

            return items;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Amazon Inspector Scan";
        }
    }
}
