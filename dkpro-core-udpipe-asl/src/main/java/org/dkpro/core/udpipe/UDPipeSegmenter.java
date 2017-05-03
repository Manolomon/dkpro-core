/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt 
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dkpro.core.udpipe;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import cz.cuni.mff.ufal.udpipe.InputFormat;
import cz.cuni.mff.ufal.udpipe.Model;
import cz.cuni.mff.ufal.udpipe.Word;
import cz.cuni.mff.ufal.udpipe.Words;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ModelProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ResourceUtils;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.SegmenterBase;

/**
 * Tokenizer and sentence splitter using OpenNLP.
 *
 */
@TypeCapability(
        outputs = {
            "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
            "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence" })
public class UDPipeSegmenter
    extends SegmenterBase
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
     * Load the model from this location instead of locating the model automatically.
     */
    public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
    @ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
    protected String modelLocation;
    
    private ModelProviderBase<Model> modelProvider;

    @Override
    public void initialize(UimaContext aContext)
        throws ResourceInitializationException
    {
        super.initialize(aContext);

        modelProvider = new ModelProviderBase<Model>()
        {
            {
                setContextObject(UDPipeSegmenter.this);
                setDefault(LOCATION, "classpath:/org/dkpro/core/udpipe/lib/" +
                        "segmenter-${language}-${variant}.properties");
                setDefault(VARIANT, "ud");

                setOverride(LOCATION, modelLocation);
                setOverride(LANGUAGE, language);
                setOverride(VARIANT, variant);
            }

            @Override
            protected Model produceResource(URL aUrl)
                throws IOException
            {
                File modelFile = ResourceUtils.getUrlAsFile(aUrl, true);
                return Model.load(modelFile.getAbsolutePath());
            }
        };
    }

    @Override
    public void process(JCas aJCas)
        throws AnalysisEngineProcessException
    {
        modelProvider.configure(aJCas.getCas());
        super.process(aJCas);
    }

    @Override
    protected void process(JCas aJCas, String aText, int aZoneBegin)
        throws AnalysisEngineProcessException
    {           
        InputFormat inputFormat = modelProvider.getResource().newTokenizer(Model.getDEFAULT());
        inputFormat.setText(aJCas.getDocumentText());

        cz.cuni.mff.ufal.udpipe.Sentence sentence = new cz.cuni.mff.ufal.udpipe.Sentence();
        
        int fromSentence = 0;
        
        String text=aJCas.getDocumentText();
        
        while (inputFormat.nextSentence(sentence)) {
            
            Words words = sentence.getWords();
            int pos = fromSentence;
            int sStart =  text.indexOf(words.get(1).getForm(),pos);
            if (sStart == -1)
                throw new IllegalStateException("Can not find the sentence  starting with word [" + words.get(1).getForm() + "] in text [" + text.substring(fromSentence)
                + "]");
            
            for (int i = 1; i < words.size(); i++) {
                Word w = words.get(i);
                pos = text.indexOf(w.getForm(),pos);
                if (pos == -1) {
                    throw new IllegalStateException("Token [" + w.getForm() + "] not found in sentence [" + text.substring(fromSentence)
                            + "]");
                }
                int tStart = pos;
                int tEnd = pos + w.getForm().length();
                pos = tEnd;
                tStart += aZoneBegin;
                tEnd += aZoneBegin;
                createToken(aJCas, tStart, tEnd);
            }
            
            int sEnd = pos;
            fromSentence = sEnd;
            sStart += aZoneBegin;
            sEnd += aZoneBegin;
            
            createSentence(aJCas, sStart, sEnd);            
            sentence = new cz.cuni.mff.ufal.udpipe.Sentence();
        }
    }
}
