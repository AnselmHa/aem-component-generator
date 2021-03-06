package com.adobe.aem.compgenerator.utils;

import com.adobe.aem.compgenerator.exceptions.GeneratorException;
import com.adobe.aem.compgenerator.models.BaseModel;
import com.adobe.aem.compgenerator.models.GenerationConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jayway.jsonpath.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateUtils {
    private static final String TEMPLATE_DEFINITIONS = "$['template-definitions']";
    private static final String TEMPLATE_COPY_PATTERN_BEFORE = TEMPLATE_DEFINITIONS + "['copy-patterns']";
    private static final String TEMPLATE_FIELDS_WITH_PLACEHOLDERS =
            TEMPLATE_DEFINITIONS + "['placeholder-patterns'].jsonPath";
    private static final String TEMPLATE_COLLECT_PATTERN_AFTER = TEMPLATE_DEFINITIONS + "['collect-patterns']";
    private static final Logger LOG = LogManager.getLogger(TemplateUtils.class);

    public static String initConfigTemplates(String dataConfigJson) {
        String dataConfigLoc = dataConfigJson;
        try {
            if (!isTemplateAvailable(dataConfigLoc)) {
                return dataConfigLoc;
            }
            // Copy template pattern to json nodes e.g. json-data properties
            dataConfigLoc = bringTemplateValuesInDataConfig(dataConfigJson, TEMPLATE_COPY_PATTERN_BEFORE);

            // Resolve JsonPath-Placeholders from copied template pattern
            List<PathValueHolder<Object>> templatePlaceholders =
                    readValuesFromJsonPath(dataConfigLoc, TEMPLATE_FIELDS_WITH_PLACEHOLDERS, null, false);
            if (!templatePlaceholders.isEmpty()) {
                dataConfigLoc = resolveRelativeJsonPathsInDataConfig(dataConfigLoc, templatePlaceholders);
            }
        } catch (Exception e) {
            throw new GeneratorException("initConfigTemplates Error while init config templates" + dataConfigLoc, e);
        }
        return dataConfigLoc;
    }

    public static String updateReplaceValueMap(GenerationConfig generationConfig, String dataConfigJson) {
        if (!isTemplateAvailable(dataConfigJson)) {
            return dataConfigJson;
        }
        // Build a template replacer Map from JsonPath-Placeholders and set it to generationConfig
        String dataConfigJsonNew = resolveCollectPatternAfter(dataConfigJson, TEMPLATE_COLLECT_PATTERN_AFTER,
                "$.['options'].['replaceValueMap']", generationConfig);
        LOG.trace("Data-config templating used: \n{}", dataConfigJson);
        return dataConfigJsonNew;
    }

    private static boolean isTemplateAvailable(String dataConfigLoc) {
        final List<String> pathsFound = readPathsFromJsonPath(dataConfigLoc, TEMPLATE_DEFINITIONS, null, false);
        if (pathsFound.isEmpty()) {
            LOG.debug("Template definitions not used");
            return false;
        }
        return true;
    }

    static String getIntendedStringFromJson(Object dataConfig) {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            StringWriter outputWriter = new StringWriter();
            DocumentContext parse;
            if (dataConfig instanceof String) {
                //only String where indented correctly
                parse = JsonPath.parse((String) dataConfig);
            } else {
                parse = JsonPath.parse(dataConfig);
            }
            mapper.writeValue(outputWriter, (parse.json()));
            return outputWriter.toString();
        } catch (IOException e) {
            throw new GeneratorException("Error getIntendedStringFromJson for " + dataConfig, e);
        }
    }

    @SuppressWarnings("unchecked")
    static String resolveCollectPatternAfter(String dataConfig, String templateCollectPatternAfter,
            String targetPathjforReplacerValueMap, GenerationConfig generationConfig) {
        dataConfig = bringTemplateValuesInDataConfig(dataConfig, templateCollectPatternAfter);
        final List<PathValueHolder<Map>> pathValueHolders =
                readValuesFromJsonPath(dataConfig, targetPathjforReplacerValueMap, null, true);
        PathValueHolder<Map> replaceValueMap = pathValueHolders.get(0);
        for (Map.Entry<String, Object> replacerEntry : ((Map<String, Object>) replaceValueMap.getValue()).entrySet()) {
            String replacerKey = replacerEntry.getKey();
            String replacerJsonPathValue = (String) replacerEntry.getValue();
            LOG.trace("replaceValueMapPath {} replacerKey {} replacerJsonPathValue {}", replaceValueMap.getPath(),
                    replacerKey, replacerJsonPathValue);
            List<String> valuesfromReplacerJsonPathValue = new ArrayList<>();
            for (PathValueHolder<Object> replacerValue : readValuesFromJsonPath(dataConfig, replacerJsonPathValue, null,
                    false)) {
                LOG.trace("Found Values: " + replacerValue.getValue().toString());
                final String valueForCollection = (String) replacerValue.getValue();
                if (StringUtils.isNotEmpty(valueForCollection)) {
                    valuesfromReplacerJsonPathValue.add(valueForCollection);
                }
            }
            String templatePlaceholders = StringUtils.join(valuesfromReplacerJsonPathValue, "");
            Map<String, String> stringsToReplaceValueMap = CommonUtils.getStringsToReplaceValueMap(generationConfig);
            LOG.trace("Replace common placeholders within template placeholders:\n{} \nMap:\n{}" + templatePlaceholders,
                    stringsToReplaceValueMap.toString());
            StringSubstitutor stringSubstitutor = new StringSubstitutor(stringsToReplaceValueMap);
            templatePlaceholders = stringSubstitutor.replace(templatePlaceholders);
            LOG.trace("Replaced \n{}" + templatePlaceholders);
            dataConfig = setDataToJsonByJsonPath(dataConfig, replaceValueMap.getPath(), "@" + replacerKey,
                    templatePlaceholders);
        }
        return dataConfig;
    }

    /**
     * Templates in data config containing relative placeholder "@{...}")" e.g. for special property item like field label.
     * Example: <p>@{label}: ${${sightly}Model.@{field}}</p> becomes <p>@{label}: ${${sightly}Model.textfieldTest}</p>
     *
     * @param dataConfig ..
     * @param templatePlaceholders
     * @return changed dataConfig
     * @throws IOException ..
     */
    private static String resolveRelativeJsonPathsInDataConfig(String dataConfig,
            List<PathValueHolder<Object>> templatePlaceholders) throws IOException {
        Map<String, String> stringsToReplaceValueMap = new LinkedHashMap<>();
        if (templatePlaceholders.isEmpty()) {
            return null;
        }
        String templateFinder = (String) templatePlaceholders.get(0).getValue();
        for (PathValueHolder<Object> objectPathValueHolder : readValuesFromJsonPath(dataConfig, templateFinder, null,
                true)) {
            String templateJasonPath = unifyJasonPath(objectPathValueHolder.getPath());
            try {
                StringWriter outputWriter = new StringWriter();
                new ObjectMapper().writeValue(outputWriter, objectPathValueHolder.getValue());
                String templateJsonValue = outputWriter.toString();
                LOG.trace("templateJsonValue {}", templateJsonValue);
                for (String templateToken : TemplateUtils.findTemplateTokens(templateJsonValue)) {
                    LOG.trace("templateToken {} for path {}", templateToken, templateJasonPath);
                    String templateParentPath = StringUtils.substringBeforeLast(templateJasonPath, ".");
                    String relativeJsonPath =
                            StringUtils.replace(StringUtils.substringBeforeLast(templateToken, "}"), "@{", "@");
                    final List<PathValueHolder<Object>> pathValueHolders =
                            readValuesFromJsonPath(dataConfig, buildJasonPath(templateParentPath, relativeJsonPath),
                                    null, true);
                    if (pathValueHolders.isEmpty()) {
                        LOG.warn("Problem reading value empty for template Json path {} relativeJsonPath {}",
                                templateParentPath, relativeJsonPath);
                    } else {
                        String tokenValue = (String) pathValueHolders.get(0).getValue();
                        LOG.trace("put templateToken {} with value  {}", templateToken, tokenValue);
                        stringsToReplaceValueMap.put(StringUtils.substringBetween(templateToken, "{", "}"), tokenValue);
                    }
                }
                StringSubstitutor stringSubstitutor = new StringSubstitutor(stringsToReplaceValueMap, "@{", "}");
                dataConfig = setDataToJsonByJsonPath(dataConfig, templateJasonPath, "@",
                        new ObjectMapper().readValue(stringSubstitutor.replace(templateJsonValue), Object.class));
            } catch (JsonProcessingException e) {
                LOG.warn("Problem reading template Json for path {}", templateJasonPath, e);
            }
        }
        return dataConfig;
    }

    static List<String> findTemplateTokens(final CharSequence text) {
        if (text == null || text.toString().trim().equals("")) {
            throw new IllegalArgumentException("Invalid text");
        }
        final Pattern pattern = Pattern.compile("(@\\{[^\\}]*\\})+");
        final Matcher matcher = pattern.matcher(text.toString());
        final List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group(0));
        }
        return tokens;
    }

    /**
     * @param dataConfigJson         ..
     * @param definitionTypeNodeName node name to search for template definition
     * @return dataConfigJson replaced by values
     */
    private static List<TemplateDefinition> readTemplateDefinition(String dataConfigJson,
            String definitionTypeNodeName) {
        List<TemplateDefinition> templateDefinitions = new ArrayList<>();
        List<PathValueHolder<Map<String, String>>> foundDefinitionTypes =
                readValuesFromJsonPath(dataConfigJson, definitionTypeNodeName + ".*", null, true);
        for (PathValueHolder<Map<String, String>> foundDefinitionType : foundDefinitionTypes) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String definitionAsJsonString = getIntendedStringFromJson(foundDefinitionType.getValue());
            try {
                templateDefinitions.add(mapper.readValue(definitionAsJsonString, TemplateDefinition.class));
            } catch (JsonProcessingException e) {
                throw new GeneratorException(
                        "Error DeserializationFeature path " + foundDefinitionType.getPath() + " for " +
                                definitionAsJsonString, e);
            }
        }
        return templateDefinitions;
    }

    /**
     * bringTemplateValuesInDataConfig
     *
     * @param dataConfigJson         ..
     * @param definitionTypeNodeName node to search for TemplateValues
     * @return dataConfigJson replaced by values
     */
    private static String bringTemplateValuesInDataConfig(String dataConfigJson, String definitionTypeNodeName) {
        String dataConfigLocal = dataConfigJson;
        List<TemplateDefinition> collectPatterns = readTemplateDefinition(dataConfigLocal, definitionTypeNodeName);
        for (TemplateDefinition collectPattern : collectPatterns) {
            String baseJsonPath = collectPattern.getbaseJsonPath();
            if (collectPattern.getTargetAttributes() != null) {
                for (Map.Entry<String, String> patternAttributes : collectPattern.getTargetAttributes().entrySet()) {
                    String replacerKey = patternAttributes.getKey();
                    String replacerJsonPathValue = patternAttributes.getValue();
                    String jsonPathToSearchInDataJson = unifyJasonPath(baseJsonPath);
                    List<String> jsonPathsToAdd =
                            readPathsFromJsonPath(dataConfigLocal, jsonPathToSearchInDataJson, null,
                                    collectPattern.isWarnMissingPaths());
                    LOG.trace("bringTemplateValuesInDataConfig - jsonPathToSearchInDataJson {} jsonPathToAdd {} found",
                            jsonPathToSearchInDataJson, jsonPathsToAdd);
                    for (String jsonPathToAdd : jsonPathsToAdd) {
                        dataConfigLocal = setDataToJsonByJsonPath(dataConfigLocal, jsonPathToAdd, replacerKey,
                                replacerJsonPathValue);
                    }

                }
            }
        }
        return dataConfigLocal;
    }

    /**
     * setDataToJsonByJsonPath missing nodes will be created with put LinkedHashMap
     *
     * @param dataConfigJson   ..
     * @param jsonPathToAdd    starts with $.
     * @param relativeJsonPath starts with @
     * @param targetValue      ..
     * @return jsonString with set value
     */
    static String setDataToJsonByJsonPath(String dataConfigJson, String jsonPathToAdd, String relativeJsonPath,
            Object targetValue) {
        LOG.trace("dataConfigJson \n{}", dataConfigJson);
        DocumentContext jsonDoc = JsonPath.using(Configuration.builder().build()).parse(dataConfigJson);
        for (String pathsFromJsonPath : readPathsFromJsonPath(dataConfigJson, jsonPathToAdd, null, true)) {
            String targetPath = buildJasonPath(pathsFromJsonPath, relativeJsonPath);
            LOG.debug("Put Node targetPath {} pathsFromJsonPath {} currentPathSegment {} ", targetPath,
                    pathsFromJsonPath, relativeJsonPath);
            String parentAdded = StringUtils.substringBefore(targetPath, ".");
            String[] pathSegmentsAfterRoot =
                    StringUtils.split(StringUtils.substringAfter(targetPath, parentAdded + "."), ".");
            for (String currentPathSegment : pathSegmentsAfterRoot) {
                Object subscription = null;
                String currentTargetPath = parentAdded + "." + currentPathSegment;
                try {
                    subscription = jsonDoc.read(currentTargetPath);
                } catch (Exception e) {
                    LOG.trace("Node {} not found", currentTargetPath);
                }
                if (subscription == null ||
                        (subscription instanceof Collection && ((Collection) subscription).size() <= 0)) {
                    try {
                        LOG.debug("Put Node parentAdded {} currentPathSegment {} ", parentAdded, currentPathSegment);
                        jsonDoc = jsonDoc.put(parentAdded, currentPathSegment, new LinkedHashMap());
                        LOG.trace("Change json parentAdded {} currentPathSegment {} ", parentAdded, currentPathSegment);
                    } catch (Exception e) {
                        throw new GeneratorException(
                                "Error creating node " + currentPathSegment + " at " + parentAdded + " ", e);
                    }
                }
                parentAdded = currentTargetPath;
            }
            LOG.trace("Set at targetPath {} targetValue {}", targetPath, targetValue);
            jsonDoc.set(targetPath, targetValue);
        }
        return jsonDoc.jsonString();
    }

    private static String buildJasonPath(String jsonPathToAdd, String relativeJsonPath) {
        String unifiedJsonPathToAdd = unifyJasonPath(jsonPathToAdd);
        if (!StringUtils.startsWithAny(unifiedJsonPathToAdd, "$")) {
            throw new GeneratorException("jsonPathToAdd [" + jsonPathToAdd + "] must start with $ or \"['$']\"");
        }
        if (!StringUtils.startsWith(relativeJsonPath, "@")) {
            throw new GeneratorException("relativeJsonPath [" + relativeJsonPath + "] must start with @");
        }
        String buildJsonPath = StringUtils.replace(relativeJsonPath, "@", unifiedJsonPathToAdd);
        LOG.trace("buildJasonPath - targetPath {} jsonPathToAdd {} relativeJsonPath {}", buildJsonPath,
                unifiedJsonPathToAdd, relativeJsonPath);
        return buildJsonPath;
    }

    static String unifyJasonPath(String jsonPathToAdd) {
        String unifiedJsonPathToAdd = jsonPathToAdd;
        if (StringUtils.startsWith(unifiedJsonPathToAdd, "['") && StringUtils.endsWith(unifiedJsonPathToAdd, "']")) {
            unifiedJsonPathToAdd =
                    StringUtils.substringBeforeLast(StringUtils.substringAfter(unifiedJsonPathToAdd, "['"), "']");
        }
        unifiedJsonPathToAdd = StringUtils.replace(unifiedJsonPathToAdd, ".['$", "['$");
        unifiedJsonPathToAdd = StringUtils.replace(unifiedJsonPathToAdd, "$[", "$.[");
        unifiedJsonPathToAdd = StringUtils.replace(unifiedJsonPathToAdd, "][", "].[");
        LOG.trace("unifyJasonPath - jsonPathToAdd {} unifiedJsonPathToAdd {}", jsonPathToAdd, unifiedJsonPathToAdd);
        return unifiedJsonPathToAdd;
    }

    static <T> List<PathValueHolder<T>> readValuesFromJsonPath(String dataConfigJson, String jasonPath,
            Predicate<String> filterPaths, boolean warnMissingPath) {
        final List<String> pathsFound = readPathsFromJsonPath(dataConfigJson, jasonPath, filterPaths, warnMissingPath);
        final List<PathValueHolder<T>> pathValues = new ArrayList<PathValueHolder<T>>();
        for (String path : pathsFound) {
            String unifyJasonPath = unifyJasonPath(path);
            LOG.trace("readValuesFromJsonPath - unifyJasonPath {}", unifyJasonPath);
            try {
                pathValues.add(new PathValueHolder<>(unifyJasonPath,
                        JsonPath.parse(dataConfigJson).delete("$..['_comment_']").read(unifyJasonPath)));
            } catch (Exception e) {
                throw new GeneratorException("Read value error on reading jsonPath '" + unifyJasonPath +
                        ". See http://jsonpath.herokuapp.com for help", e);
            }
        }
        return pathValues;
    }

    static List<String> readPathsFromJsonPath(String dataConfigJson, String readPath, Predicate<String> filterPaths,
            boolean warnMissing) {
        Configuration conf = Configuration.builder().options(Option.AS_PATH_LIST).build();
        String unifyJasonPath = unifyJasonPath(readPath);
        LOG.trace("readPathsFromJsonPath - readPath {}", unifyJasonPath);
        List<String> pathList = new ArrayList<>();
        try {
            pathList = JsonPath.using(conf).parse(dataConfigJson).delete("$..['_comment_']").read(unifyJasonPath);
            if (filterPaths != null) {
                return Arrays.asList(pathList.stream().filter(filterPaths).toArray(String[]::new));
            }
        } catch (PathNotFoundException e) {
            LOG.trace("readPathsFromJsonPath - dataConfigJson {}", dataConfigJson);
            if (warnMissing) {
                LOG.warn("readPathsFromJsonPath - readPath " + unifyJasonPath, e);
            } else {
                LOG.debug("readPathsFromJsonPath - readPath " + unifyJasonPath, e);
            }
        } catch (Exception e) {
            String urlForTesting = null;
            try {
                urlForTesting = URLEncoder.encode("\"http://jsonpath.herokuapp.com/?path=" + unifyJasonPath, "UTF-8");
            } catch (UnsupportedEncodingException er) {
                LOG.error("Error encoding parameter {}", er.getMessage(), er);
            }
            throw new GeneratorException(
                    "Read path error on reading jsonPath '" + unifyJasonPath + "' \ntest your jsonPath with " +
                            urlForTesting + "\"\njson: " + dataConfigJson, e);
        }
        return pathList;
    }

    static class TemplateDefinition implements BaseModel {

        @JsonProperty("baseJsonPath")
        private String baseJsonPath;

        @JsonProperty("targetAttributes")
        private Map<String, String> targetAttributes;

        @JsonProperty("warnMissingPaths")
        private boolean warnMissingPaths;

        String getbaseJsonPath() {
            return baseJsonPath;
        }

        void setbaseJsonPath(String baseJsonPath) {
            this.baseJsonPath = baseJsonPath;
        }

        Map<String, String> getTargetAttributes() {
            return targetAttributes;
        }

        public void setTargetAttributes(Map<String, String> targetAttributes) {
            this.targetAttributes = targetAttributes;
        }

        public boolean isWarnMissingPaths() {
            return warnMissingPaths;
        }

        public void setWarnMissingPaths(boolean warnMissingPaths) {
            this.warnMissingPaths = warnMissingPaths;
        }

        @Override
        public boolean isValid() {
            return this.baseJsonPath != null;
        }
    }


    static class PathValueHolder<T> {
        private final String path;
        private final T value;

        PathValueHolder(String path, T value) {
            this.path = path;
            this.value = value;
        }

        public String getPath() {
            return path;
        }

        public T getValue() {
            return value;
        }
    }
}
