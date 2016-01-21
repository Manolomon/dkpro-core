/*******************************************************************************
 * Copyright 2013
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.tudarmstadt.ukp.dkpro.core.io.conll;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.fit.factory.JCasBuilder;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import de.tudarmstadt.ukp.dkpro.core.api.io.IobDecoder;
import de.tudarmstadt.ukp.dkpro.core.api.io.JCasResourceCollectionReader_ImplBase;
import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProvider;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;

/**
 * <p>Reads by default the original the CoNLL 2002 named entity format. By default, columns are separated by a single space, like
 * illustrated below.</p>
 * 
 * <pre><code>
 * Wolff      B-PER
 * ,          O
 * currently  O
 * a          O
 * journalist O
 * in         O
 * Argentina  B-LOC
 * ,          O
 * played     O
 * with       O
 * Del        B-PER
 * Bosque     I-PER
 * in         O
 * the        O
 * final      O
 * years      O
 * of         O
 * the        O
 * seventies  O
 * in         O
 * Real       B-ORG
 * Madrid     I-ORG
 * .          O
 * </code></pre>
 * 
 * <ol>
 * <li>FORM - token</li>
 * <li>NER - named entity (BIO encoded)</li>
 * </ol>
 * 
 * <p>Sentences are separated by a blank new line.</p>
 * 
 * @see <a href="http://www.clips.ua.ac.be/conll2002/ner/">CoNLL 2002 shared task</a>
 *
 * <p>The reader is also compatible with the Conll-based GermEval 2014 named entity format,
 * in which the columns are separated by a tab, and there is an extra column for embedded named entities (see below). 
 * Currently, the reader only reads the outer named entities, not the embedded ones.</p>
 * 
 * <pre>
 * The following snippet shows an example of the TSV format 
 * # http://de.wikipedia.org/wiki/Manfred_Korfmann [2009-10-17]
 * 1 Aufgrund O O
 * 2 seiner O O
 * 3 Initiative O O
 * 4 fand O O
 * 5 2001/2002 O O
 * 6 in O O
 * 7 Stuttgart B-LOC O
 * 8 , O O
 * 9 Braunschweig B-LOC O
 * 10 und O O
 * 11 Bonn B-LOC O
 * 12 eine O O
 * 13 große O O
 * 14 und O O
 * 15 publizistisch O O
 * 16 vielbeachtete O O
 * 17 Troia-Ausstellung B-LOCpart O
 * 18 statt O O
 * 19 , O O
 * 20 „ O O
 * 21 Troia B-OTH B-LOC
 * 22 - I-OTH O
 * 23 Traum I-OTH O
 * 24 und I-OTH O
 * 25 Wirklichkeit I-OTH O
 * 26 “ O O
 * 27 . O O
 * </pre>
 * 
 * <ol>
 * <li>WORD_NUMBER - token number</li>
 * <li>FORM - token</li>
 * <li>NER1 - outer named entity (BIO encoded)</li>
 * <li>NER2 - embedded named entity (BIO encoded)</li>
 * </ol>

 * The sentence is encoded as one token per line, with information provided in tab-separated columns. 
 * The first column contains either a #, which signals the source the sentence is cited from and the date it was retrieved, 
 * or the token number within the sentence. The second column contains the token.
 * Name spans are encoded in the BIO-scheme. Outer spans are encoded in the third column, 
 * embedded spans in the fourth column.
 * 
 * @see <a href="https://sites.google.com/site/germeval2014ner/data">GermEval 2014 NER task</a> 
 */
@TypeCapability(outputs = { "de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
        "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
        "de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity"})
public class Conll2002Reader
    extends JCasResourceCollectionReader_ImplBase
{
	/**
	 * Column positions
	 */
	private static final int FORM = 0;
    private static final int IOB  = 1;
    private static final int IOB_EMBEDDED = 2;
    
    /** 
     * Constants
     */
    private static final String TAB   = "\t";
    private static final String SPACE = " ";
    private static final char NUMBER_SIGN = '#';

    /**
     * Column separator value
     */
    private String columnSeparator = SPACE;
    
    /**
     * Column separator parameter.
     */
    public static final String COLUMN_SEPARATOR = ComponentParameters.PARAM_COLUMN_SEPARATOR;
    @ConfigurationParameter(name = PARAM_ENCODING, mandatory = false, defaultValue = "space")
    private String paramColumnSeparator;

    /**
     * Character encoding of the input data.
     */
    public static final String PARAM_ENCODING = ComponentParameters.PARAM_SOURCE_ENCODING;
    @ConfigurationParameter(name = PARAM_ENCODING, mandatory = true, defaultValue = "UTF-8")
    private String encoding;

    /**
     * The language.
     */
    public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
    @ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
    private String language;

    /**
     * Use the {@link String#intern()} method on tags. This is usually a good idea to avoid
     * spamming the heap with thousands of strings representing only a few different tags.
     *
     * Default: {@code true}
     */
    public static final String PARAM_INTERN_TAGS = ComponentParameters.PARAM_INTERN_TAGS;
    @ConfigurationParameter(name = PARAM_INTERN_TAGS, mandatory = false, defaultValue = "true")
    private boolean internTags;

    /**
     * Write named entity information.
     *
     * Default: {@code true}
     */
    public static final String PARAM_READ_NAMED_ENTITY = ComponentParameters.PARAM_READ_NAMED_ENTITY;
    @ConfigurationParameter(name = PARAM_READ_NAMED_ENTITY, mandatory = true, defaultValue = "true")
    private boolean namedEntityEnabled;

    /**
     * Write embedded named entity information.
     *
     * Default: {@code false}
     */
    public static final String PARAM_READ_EMBEDDED_NAMED_ENTITY = ComponentParameters.PARAM_READ_EMBEDDED_NAMED_ENTITY;
    @ConfigurationParameter(name = PARAM_READ_EMBEDDED_NAMED_ENTITY, mandatory = false, defaultValue = "false")
    private boolean embeddedNamedEntityEnabled;

//    /**
//     * Load the chunk tag to UIMA type mapping from this location instead of locating
//     * the mapping automatically.
//     */
//    public static final String PARAM_NAMED_ENTITY_MAPPING_LOCATION = ComponentParameters.PARAM_NAMED_ENTITY_MAPPING_LOCATION;
//    @ConfigurationParameter(name = PARAM_NAMED_ENTITY_MAPPING_LOCATION, mandatory = false)
//    protected String namedEntityMappingLocation;
//    
    private MappingProvider namedEntityMappingProvider;

    @Override
    public void initialize(UimaContext aContext)
        throws ResourceInitializationException
    {
        super.initialize(aContext);
        
        namedEntityMappingProvider = new MappingProvider();
//        namedEntityMappingProvider.setDefault(MappingProvider.LOCATION, "classpath:/de/tudarmstadt/ukp/"
//                + "dkpro/core/api/syntax/tagset/${language}-${chunk.tagset}-chunk.map");
        namedEntityMappingProvider.setDefault(MappingProvider.LOCATION, "classpath:/there/is/no/mapping/yet");
        namedEntityMappingProvider.setDefault(MappingProvider.BASE_TYPE, NamedEntity.class.getName());
//        namedEntityMappingProvider.setOverride(MappingProvider.LOCATION, namedEntityMappingLocation);
//        namedEntityMappingProvider.setOverride(MappingProvider.LANGUAGE, language);

        if (paramColumnSeparator.equals("tab")) {
        	columnSeparator = TAB;
        } else { 
        	columnSeparator = SPACE;
        }
    }
    
    @Override
    public void getNext(JCas aJCas)
        throws IOException, CollectionException
    {
        try{
            if (namedEntityEnabled) {
                namedEntityMappingProvider.configure(aJCas.getCas());
            }
        }
        catch(AnalysisEngineProcessException e){
            throw new IOException(e);
        }
        
        Resource res = nextFile();
        initCas(aJCas, res);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(res.getInputStream(), encoding));
            convert(aJCas, reader);
        }
        finally {
            closeQuietly(reader);
        }
    }

    private void convert(JCas aJCas, BufferedReader aReader)
        throws IOException
    {
        JCasBuilder doc = new JCasBuilder(aJCas);

        Type namedEntityType = JCasUtil.getType(aJCas, NamedEntity.class);
        Feature namedEntityValue = namedEntityType.getFeatureByBaseName("value");
        IobDecoder decoder = new IobDecoder(aJCas.getCas(), namedEntityValue, namedEntityMappingProvider);
        decoder.setInternTags(internTags);
        
        List<String[]> words;
        while ((words = readSentence(aReader)) != null) {
            if (words.isEmpty()) {
                continue;
            }

            int sentenceBegin = doc.getPosition();
            int sentenceEnd = sentenceBegin;

            List<Token> tokens = new ArrayList<Token>();
            String[] namedEntityTags = new String[words.size()];
            
            // Tokens, POS
            int i = 0;
            for (String[] word : words) {
                // Read token
                Token token = doc.add(word[FORM], Token.class);
                sentenceEnd = token.getEnd();
                doc.add(" ");
                
                tokens.add(token);
                namedEntityTags[i] = word[IOB];
                i++;
            }
            
            if (namedEntityEnabled) {
                decoder.decode(tokens, namedEntityTags);
            }

            // Sentence
            Sentence sentence = new Sentence(aJCas, sentenceBegin, sentenceEnd);
            sentence.addToIndexes();

            // Once sentence per line.
            doc.add("\n");
        }

        doc.close();
    }

    /**
     * Read a single sentence.
     */
    private List<String[]> readSentence(BufferedReader aReader)
        throws IOException
    {
        List<String[]> words = new ArrayList<String[]>();
        String line;
        while ((line = aReader.readLine()) != null) {
            if (StringUtils.isBlank(line)) {
                break; // End of sentence
            }

            String[] fields = line.split(columnSeparator);

           	if (!embeddedNamedEntityEnabled && fields.length != 2) {
                throw new IOException(
                        "Invalid file format. Line needs to have 2 " + paramColumnSeparator + "-separated fields.");
            } else if (embeddedNamedEntityEnabled && fields.length != 3) {
                    throw new IOException(
                            "Invalid file format. Line needs to have 3 " + paramColumnSeparator + "-separated fields.");
            }
            words.add(fields);
        }

        if (line == null && words.isEmpty()) {
            return null;
        }
        else {
            return words;
        }
    }
}