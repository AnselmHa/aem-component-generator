/*
 * #%L
 * AEM Component Generator
 * %%
 * Copyright (C) 2019 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.adobe.aem.compgenerator;

import com.adobe.aem.compgenerator.exceptions.GeneratorException;
import com.adobe.aem.compgenerator.javacodemodel.JavaCodeModel;
import com.adobe.aem.compgenerator.models.GenerationConfig;
import com.adobe.aem.compgenerator.utils.CommonUtils;
import com.adobe.aem.compgenerator.utils.ComponentUtils;
import com.adobe.aem.compgenerator.utils.TemplateUtils;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Root of the AEM Component generator.
 *
 * AemCompGenerator reads the json data file input and creates folder, file
 * structure of an AEM component and sling model interface with member values
 * and getters.
 */
public class AemCompGenerator {
    private static final Logger LOG = LogManager.getLogger(AemCompGenerator.class);

    public static void main(String[] args) {
        try {
            String configPath = "data-config.json";
            if (args.length > 0) {
                configPath = args[0];
            }

            File configFile = new File(configPath);

            if (CommonUtils.isFileBlank(configFile)) {
                throw new GeneratorException("Config file missing / empty.");
            }

            //creates template structure
            String configAfterTemplateInit = TemplateUtils
                    .initConfigTemplates(FileUtils.readFileToString(new File(configPath), StandardCharsets.UTF_8));

            //updates replacer value map from Templates
            String configUpdateReplacerMap =
                    TemplateUtils.updateReplaceValueMap(createGenerationConfig(configFile), configAfterTemplateInit);

            FileUtils.writeStringToFile(new File("target/" + configPath), configUpdateReplacerMap,
                    StandardCharsets.UTF_8);
            configFile = new File("target/" + configPath);

            GenerationConfig config = createGenerationConfig(configFile);

            //builds component folder and file structure.
            ComponentUtils generatorUtils = new ComponentUtils(config);
            generatorUtils.buildComponent(configFile.getPath());

            //builds sling model based on config.
            if (config.getOptions() != null && config.getOptions().isHasSlingModel()) {
                JavaCodeModel javaCodeModel = new JavaCodeModel();
                javaCodeModel.buildSlingModel(config);
            }
        } catch (Exception e) {
            LOG.error("Failed to generate aem component.", e);
        }
    }

    public static GenerationConfig createGenerationConfig(File configFile) {
        GenerationConfig config = CommonUtils.getComponentData(configFile);

        if (config == null) {
            throw new GeneratorException("Config file is empty / null !!");
        }

        if (!config.isValid() || !CommonUtils.isModelValid(config.getProjectSettings())) {
            throw new GeneratorException("Mandatory fields missing in the data-config.json !");
        }

        CommonUtils.updateCompDirFromConfig(config);
        return config;
    }
}
