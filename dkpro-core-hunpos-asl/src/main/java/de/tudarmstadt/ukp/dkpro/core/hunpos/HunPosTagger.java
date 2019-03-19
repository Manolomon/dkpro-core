/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.dkpro.core.hunpos;

import static org.apache.uima.fit.util.JCasUtil.select;
import static org.apache.uima.fit.util.JCasUtil.selectCovered;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.util.List;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Type;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ResourceMetaData;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.pos.POSUtils;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.CasConfigurableProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProvider;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProviderFactory;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ResourceUtils;
import de.tudarmstadt.ukp.dkpro.core.api.resources.RuntimeProvider;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import eu.openminted.share.annotations.api.Component;
import eu.openminted.share.annotations.api.DocumentationResource;
import eu.openminted.share.annotations.api.constants.OperationType;

/**
 * Part-of-Speech annotator using HunPos. Requires {@link Sentence}s to be annotated
 * before.
 *
 * <p><b>References</b></p>
 * <ul>
 * <li>HALÁCSY, Péter; KORNAI, András; ORAVECZ, Csaba. HunPos: an open source trigram tagger. In:
 * Proceedings of the 45th annual meeting of the ACL on interactive poster and demonstration
 * sessions. Association for Computational Linguistics, 2007. S. 209-212.
 * <a href="http://aclweb.org/anthology/P/P07/P07-2053.pdf">(pdf)</a>
 * <a href="http://aclweb.org/anthology/P/P07/P07-2053.bib">(bibtex)</a></li>
 * </ul>
 */
@Component(OperationType.PART_OF_SPEECH_TAGGER)
@DocumentationResource("${docbase}/component-reference.html#engine-${shortClassName}")
@ResourceMetaData(name = "HunPos POS-Tagger")
@TypeCapability(
        inputs = { 
                "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence" }, 
        outputs = { 
                "de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS" })
public class HunPosTagger
    extends JCasAnnotator_ImplBase
{
    /**
     * Use this language instead of the document language to resolve the model.
     */
    public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
    @ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
    protected String language;

    /**
     * Override the default variant used to locate the model.
     */
    public static final String PARAM_VARIANT = ComponentParameters.PARAM_VARIANT;
    @ConfigurationParameter(name = PARAM_VARIANT, mandatory = false)
    protected String variant;

    /**
     * URI of the model artifact. This can be used to override the default model resolving 
     * mechanism and directly address a particular model.
     * 
     * <p>The URI format is {@code mvn:${groupId}:${artifactId}:${version}}. Remember to set
     * the variant parameter to match the artifact. If the artifact contains the model in
     * a non-default location, you  also have to specify the model location parameter, e.g.
     * {@code classpath:/model/path/in/artifact/model.bin}.</p>
     */
    public static final String PARAM_MODEL_ARTIFACT_URI = 
            ComponentParameters.PARAM_MODEL_ARTIFACT_URI;
    @ConfigurationParameter(name = PARAM_MODEL_ARTIFACT_URI, mandatory = false)
    protected String modelArtifactUri;
    
    /**
     * Load the model from this location instead of locating the model automatically.
     */
    public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
    @ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
    protected String modelLocation;

    /**
     * Enable/disable type mapping.
     */
    public static final String PARAM_MAPPING_ENABLED = ComponentParameters.PARAM_MAPPING_ENABLED;
    @ConfigurationParameter(name = PARAM_MAPPING_ENABLED, mandatory = true, defaultValue = 
            ComponentParameters.DEFAULT_MAPPING_ENABLED)
    protected boolean mappingEnabled;

    /**
     * Load the part-of-speech tag to UIMA type mapping from this location instead of locating the
     * mapping automatically.
     */
    public static final String PARAM_POS_MAPPING_LOCATION = 
            ComponentParameters.PARAM_POS_MAPPING_LOCATION;
    @ConfigurationParameter(name = PARAM_POS_MAPPING_LOCATION, mandatory = false)
    protected String posMappingLocation;

    /**
     * Log the tag set(s) when a model is loaded.
     */
    public static final String PARAM_PRINT_TAGSET = ComponentParameters.PARAM_PRINT_TAGSET;
    @ConfigurationParameter(name = PARAM_PRINT_TAGSET, mandatory = true, defaultValue = "false")
    protected boolean printTagSet;

    private CasConfigurableProviderBase<File> modelProvider;
    private RuntimeProvider runtimeProvider;
    private MappingProvider posMappingProvider;

    @Override
    public void initialize(UimaContext aContext)
        throws ResourceInitializationException
    {
        super.initialize(aContext);

        modelProvider = new CasConfigurableProviderBase<File>()
        {
            {
                setContextObject(HunPosTagger.this);

                setDefault(ARTIFACT_ID, "${groupId}.hunpos-model-tagger-${language}-${variant}");
                setDefault(LOCATION, "classpath:/de/tudarmstadt/ukp/dkpro/core/hunpos/lib/"
                        + "tagger-${language}-${variant}.model");
                setDefault(VARIANT, "default");
                setDefaultVariantsLocation("de/tudarmstadt/ukp/dkpro/core/hunpos/lib/tagger-default-variants.map");

                setOverride(LOCATION, modelLocation);
                setOverride(LANGUAGE, language);
                setOverride(VARIANT, variant);
            }

            @Override
            protected File produceResource(URL aUrl)
                throws IOException
            {
                return ResourceUtils.getUrlAsFile(aUrl, true);
            }
        };

        // provider for the sfst binary
        runtimeProvider = new RuntimeProvider(
                "classpath:/de/tudarmstadt/ukp/dkpro/core/hunpos/bin/");

        posMappingProvider = MappingProviderFactory.createPosMappingProvider(this,
                posMappingLocation, language, modelProvider);
    }

    @Override
    public void process(JCas aJCas)
        throws AnalysisEngineProcessException
    {
        CAS cas = aJCas.getCas();

        modelProvider.configure(cas);
        posMappingProvider.configure(cas);

        String modelEncoding = (String) modelProvider.getResourceMetaData().get("model.encoding");
        if (modelEncoding == null) {
            throw new AnalysisEngineProcessException(
                    new Throwable("Model should contain encoding metadata"));
        }
        File model = modelProvider.getResource();
        File executable;

        try {
            executable = runtimeProvider.getFile("hunpos-tag");
        }
        catch (IOException e) {
            throw new AnalysisEngineProcessException(e);
        }

        ProcessBuilder pb = new ProcessBuilder(executable.getAbsolutePath(),
                model.getAbsolutePath());
        pb.redirectError(Redirect.INHERIT);

        StringBuffer lastOut = new StringBuffer();
        String lastIn = null;
        boolean success = false;
        Process proc = null;
        try {
            proc = pb.start();

            PrintWriter out = new PrintWriter(new OutputStreamWriter(proc.getOutputStream(),
                    modelEncoding));
            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(),
                    modelEncoding));

            for (Sentence sentence : select(aJCas, Sentence.class)) {
                List<Token> tokens = selectCovered(Token.class, sentence);

                // Skip empty sentences
                if (tokens.isEmpty()) {
                    continue;
                }

                // Send full sentence
                for (Token token : tokens) {
                    lastOut.append(token.getText()).append(' ');
                    out.printf("%s%n", token.getText());
                }
                out.printf("%n");
                out.flush();

                // Read sentence tags
                String[] tags = new String[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) {
                    lastIn = in.readLine();
                    tags[i] = lastIn.split("\t", 2)[1].trim();
                }
                in.readLine(); // Read extra new line after sentence

                int i = 0;
                for (Token t : tokens) {
                    Type posTag = posMappingProvider.getTagType(tags[i]);
                    POS posAnno = (POS) cas.createAnnotation(posTag, t.getBegin(), t.getEnd());
                    String tag = tags[i];
                    posAnno.setPosValue(tag != null ? tag.intern() : null);
                    POSUtils.assignCoarseValue(posAnno);
                    posAnno.addToIndexes();
                    t.setPos(posAnno);
                    i++;
                }

                lastOut.setLength(0);
            }

            success = true;
        }
        catch (IOException e) {
            throw new AnalysisEngineProcessException(e);
        }
        finally {
            if (!success) {
                getLogger().error("Sent before error: [" + lastOut + "]");
                getLogger().error("Last response before error: [" + lastIn + "]");
            }
            if (proc != null) {
                proc.destroy();
            }
        }
    }

    @Override
    public void destroy()
    {
        runtimeProvider.uninstall();
        super.destroy();
    }
}
