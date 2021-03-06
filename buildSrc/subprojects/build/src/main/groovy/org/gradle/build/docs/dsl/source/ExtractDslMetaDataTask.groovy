/*
 * Copyright 2010 the original author or authors.
 *
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
 */
package org.gradle.build.docs.dsl.source

import com.github.javaparser.JavaParser
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import org.gradle.api.Action
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction
import org.gradle.build.docs.dsl.source.model.ClassMetaData
import org.gradle.build.docs.dsl.source.model.TypeMetaData
import org.gradle.build.docs.model.ClassMetaDataRepository
import org.gradle.build.docs.model.SimpleClassMetaDataRepository
import org.gradle.build.docs.DocGenerationException
import org.gradle.api.Transformer

/**
 * Extracts meta-data from the Groovy and Java source files which make up the Gradle API. Persists the meta-data to a file
 * for later use in generating documentation for the DSL, such as by {@link org.gradle.build.docs.dsl.docbook.AssembleDslDocTask}.
 */
@CacheableTask
class ExtractDslMetaDataTask extends SourceTask {
    @OutputFile
    def File destFile

    /**
     * {@inheritDoc}
     */
    @Override
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public FileTree getSource() {
        return super.getSource();
    }

    @TaskAction
    def extract() {
        Date start = new Date()

        //parsing all input files into metadata
        //and placing them in the repository object
        SimpleClassMetaDataRepository<ClassMetaData> repository = new SimpleClassMetaDataRepository<ClassMetaData>()
        int counter = 0
        source.each { File f ->
            parse(f, repository)
            counter++
        }

        //updating/modifying the metadata and making sure every type reference across the metadata is fully qualified
        //so, the superClassName, interafaces and types needed by declared properties and declared methods will have fully qualified name
        TypeNameResolver resolver = new TypeNameResolver(repository)
        repository.each { name, metaData ->
            fullyQualifyAllTypeNames(metaData, resolver)
        }
        repository.store(destFile)

        Date stop = new Date()
        TimeDuration elapsedTime = TimeCategory.minus(stop, start)
        println "Parsed $counter classes in ${elapsedTime}"
    }

    def parse(File sourceFile, ClassMetaDataRepository<ClassMetaData> repository) {
        if (!sourceFile.name.endsWith('.java')) {
            throw new DocGenerationException("Parsing non-Java files is not supported: $sourceFile")
        }
        try {
            JavaParser.parse(sourceFile).accept(new SourceMetaDataVisitor(), repository)
        } catch (Exception e) {
            throw new DocGenerationException("Could not parse '$sourceFile'.", e)
        }
    }

    def fullyQualifyAllTypeNames(ClassMetaData classMetaData, TypeNameResolver resolver) {
        try {
            classMetaData.resolveTypes(new Transformer<String, String>(){
                String transform(String i) {
                    return resolver.resolve(i, classMetaData)
                }
            })
            classMetaData.visitTypes(new Action<TypeMetaData>() {
                void execute(TypeMetaData t) {
                    resolver.resolve(t, classMetaData)
                }
            })
        } catch (Exception e) {
            throw new RuntimeException("Could not resolve types in class '$classMetaData.className'.", e)
        }
    }
}
