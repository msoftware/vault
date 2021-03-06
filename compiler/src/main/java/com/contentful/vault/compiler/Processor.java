/*
 * Copyright (C) 2015 Contentful GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.contentful.vault.compiler;

import com.contentful.java.cda.Constants.CDAResourceType;
import com.contentful.vault.ContentType;
import com.contentful.vault.Field;
import com.contentful.vault.FieldMeta;
import com.contentful.vault.Resource;
import com.contentful.vault.Space;
import com.google.common.base.Joiner;
import com.squareup.javapoet.ClassName;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.contentful.vault.Constants.SUFFIX_MODEL;
import static com.contentful.vault.Constants.SUFFIX_SPACE;
import static javax.tools.Diagnostic.Kind.ERROR;

public class Processor extends AbstractProcessor {
  private Elements elementUtils;

  private Types typeUtils;

  private Filer filer;

  private static final String FQ_ASSET = "com.contentful.vault.Asset";

  @Override public Set<String> getSupportedAnnotationTypes() {
    Set<String> types = new LinkedHashSet<String>();
    types.add(ContentType.class.getCanonicalName());
    types.add(Space.class.getCanonicalName());
    return types;
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    elementUtils = processingEnv.getElementUtils();
    typeUtils = processingEnv.getTypeUtils();
    filer = processingEnv.getFiler();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Map<TypeElement, Injection> targets = findAndParseTargets(roundEnv);
    for (Map.Entry<TypeElement, Injection> entry : targets.entrySet()) {
      TypeElement typeElement = entry.getKey();
      try {
        Injection injection = entry.getValue();
        injection.brewJava().writeTo(filer);
      } catch (Exception e) {
        error(typeElement, "Failed writing injection for \"%s\", message: %s",
            typeElement.getQualifiedName(), e.getMessage());
      }
    }
    return true;
  }

  private Map<TypeElement, Injection> findAndParseTargets(RoundEnvironment env) {
    Map<TypeElement, ModelInjection> modelTargets =
        new LinkedHashMap<TypeElement, ModelInjection>();

    Map<TypeElement, SpaceInjection> spaceTargets =
        new LinkedHashMap<TypeElement, SpaceInjection>();

    // Parse ContentType bindings
    for (Element element : env.getElementsAnnotatedWith(ContentType.class)) {
      try {
        parseContentType((TypeElement) element, modelTargets);
      } catch (Exception e) {
        parsingError(element, ContentType.class, e);
      }
    }

    // Parse Space bindings
    for (Element element : env.getElementsAnnotatedWith(Space.class)) {
      try {
        parseSpace((TypeElement) element, spaceTargets, modelTargets);
      } catch (Exception e) {
        parsingError(element, Space.class, e);
      }
    }

    Map<TypeElement, Injection> result = new LinkedHashMap<TypeElement, Injection>();
    result.putAll(modelTargets);
    result.putAll(spaceTargets);
    return result;
  }

  private void parseSpace(TypeElement element, Map<TypeElement, SpaceInjection> spaceTargets,
      Map<TypeElement, ModelInjection> modelTargets) {

    Space annotation = element.getAnnotation(Space.class);
    String id = annotation.value();
    if (id.isEmpty()) {
      error(element, "@%s id may not be empty. (%s)",
          Space.class.getSimpleName(),
          element.getQualifiedName());
      return;
    }

    TypeMirror spaceMirror = elementUtils.getTypeElement(Space.class.getName()).asType();
    List<ModelInjection> includedModels = new ArrayList<ModelInjection>();
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (typeUtils.isSameType(mirror.getAnnotationType(), spaceMirror)) {
        Set<? extends Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>> items =
            mirror.getElementValues().entrySet();

        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : items) {
          if ("models".equals(entry.getKey().getSimpleName().toString())) {
            List l = (List) entry.getValue().getValue();
            if (l.size() == 0) {
              error(element, "@%s models must not be empty. (%s)",
                  Space.class.getSimpleName(),
                  element.getQualifiedName());
              return;
            }

            Set<String> modelIds = new LinkedHashSet<String>();
            for (Object model : l) {
              Element e = ((Type) ((Attribute) model).getValue()).asElement();
              //noinspection SuspiciousMethodCalls
              ModelInjection modelInjection = modelTargets.get(e);
              if (modelInjection == null) {
                return;
              } else {
                String rid = modelInjection.remoteId;
                if (!modelIds.add(rid)) {
                  error(element, "@%s includes multiple models with the same id \"%s\". (%s)",
                      Space.class.getSimpleName(), rid, element.getQualifiedName());
                  return;
                }
                includedModels.add(modelInjection);
              }
            }
          }
        }
      }
    }

    ClassName injectionClassName = getInjectionClassName(element, SUFFIX_SPACE);
    String dbName = "space_" + SqliteUtils.hashForId(id);
    int dbVersion = annotation.dbVersion();
    SpaceInjection injection =
        new SpaceInjection(id, injectionClassName, element, includedModels, dbName, dbVersion);
    spaceTargets.put(element, injection);
  }

  private void parseContentType(TypeElement element, Map<TypeElement, ModelInjection> targets) {
    String id = element.getAnnotation(ContentType.class).value();
    if (id.isEmpty()) {
      error(element, "@%s id may not be empty. (%s)",
          ContentType.class.getSimpleName(),
          element.getQualifiedName());
      return;
    }

    if (!isSubtypeOfType(element.asType(), Resource.class.getName())) {
      error(element,
          "Classes annotated with @%s must extend \"" + Resource.class.getName() + "\". (%s)",
          ContentType.class.getSimpleName(),
          element.getQualifiedName());
      return;
    }

    Set<FieldMeta> fields = new LinkedHashSet<FieldMeta>();
    Set<String> memberIds = new LinkedHashSet<String>();
    for (Element enclosedElement : element.getEnclosedElements()) {
      Field field = enclosedElement.getAnnotation(Field.class);
      if (field == null) {
        continue;
      }

      String fieldId = field.value();
      if (fieldId.isEmpty()) {
        fieldId = enclosedElement.getSimpleName().toString();
      }

      if (!memberIds.add(fieldId)) {
        error(element,
            "@%s for the same id (\"%s\") was used multiple times in the same class. (%s)",
            Field.class.getSimpleName(), fieldId, element.getQualifiedName());
        return;
      }

      FieldMeta.Builder fieldBuilder = FieldMeta.builder();
      if (isList(enclosedElement)) {
        DeclaredType declaredType = (DeclaredType) enclosedElement.asType();
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() == 0) {
          error(element,
              "Array fields must have a type parameter specified. (%s.%s)",
              element.getQualifiedName(),
              enclosedElement.getSimpleName());
          return;
        }

        TypeMirror arrayType = typeArguments.get(0);
        if (!isValidListType(arrayType)) {
          error(element,"Invalid list type \"%s\" specified. (%s.%s)",
              arrayType.toString(),
              element.getQualifiedName(),
              enclosedElement.getSimpleName());
          return;
        }

        String sqliteType = null;
        if (String.class.getName().equals(arrayType.toString())) {
          sqliteType = SqliteUtils.typeForClass(List.class.getName());
        }

        fieldBuilder.setSqliteType(sqliteType).setArrayType(arrayType.toString());
      } else {
        TypeMirror enclosedType = enclosedElement.asType();
        String linkType = getLinkType(enclosedType);
        String sqliteType = null;
        if (linkType == null) {
          sqliteType = SqliteUtils.typeForClass(enclosedType.toString());
          if (sqliteType == null) {
            error(element,
                "@%s specified for unsupported type (\"%s\"). (%s.%s)",
                Field.class.getSimpleName(),
                enclosedType.toString(),
                element.getQualifiedName(),
                enclosedElement.getSimpleName());
            return;
          }
        }

        fieldBuilder.setSqliteType(sqliteType)
            .setLinkType(linkType);
      }

      fields.add(fieldBuilder.setId(fieldId)
          .setName(enclosedElement.getSimpleName().toString())
          .setType(enclosedElement.asType())
          .build());
    }

    ClassName injectionClassName = getInjectionClassName(element, SUFFIX_MODEL);
    String tableName = "entry_" + SqliteUtils.hashForId(id);

    ModelInjection injection =
        new ModelInjection(id, injectionClassName, element, tableName, fields);

    targets.put(element, injection);
  }

  private boolean isValidListType(TypeMirror typeMirror) {
    return isSubtypeOfType(typeMirror, String.class.getName()) ||
        isSubtypeOfType(typeMirror, Resource.class.getName());
  }

  private boolean isList(Element element) {
    TypeMirror typeMirror = element.asType();
    if (List.class.getName().equals(typeMirror.toString())) {
      return true;
    }
    return typeMirror instanceof DeclaredType && List.class.getName().equals(
        ((DeclaredType) typeMirror).asElement().toString());
  }

  private ClassName getInjectionClassName(TypeElement typeElement, String suffix) {
    ClassName specClassName = ClassName.get(typeElement);
    return ClassName.get(specClassName.packageName(),
        Joiner.on('$').join(specClassName.simpleNames()) + suffix);
  }

  private String getLinkType(TypeMirror typeMirror) {
    CDAResourceType resourceType = null;

    if (isSubtypeOfType(typeMirror, Resource.class.getName())) {
      if (isSubtypeOfType(typeMirror, FQ_ASSET)) {
        resourceType = CDAResourceType.Asset;
      } else {
        resourceType = CDAResourceType.Entry;
      }
    }

    if (resourceType == null) {
      return null;
    }

    return resourceType.toString();
  }

  private void parsingError(Element element, Class<? extends Annotation> annotation, Exception e) {
    StringWriter stackTrace = new StringWriter();
    e.printStackTrace(new PrintWriter(stackTrace));
    error(element, "Unable to parse @%s injection.\n\n%s", annotation.getSimpleName(), stackTrace);
  }

  private void error(Element element, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    processingEnv.getMessager().printMessage(ERROR, message, element);
  }

  private boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
    if (otherType.equals(typeMirror.toString())) {
      return true;
    }
    if (!(typeMirror instanceof DeclaredType)) {
      return false;
    }
    DeclaredType declaredType = (DeclaredType) typeMirror;
    List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
    if (typeArguments.size() > 0) {
      StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
      typeString.append('<');
      for (int i = 0; i < typeArguments.size(); i++) {
        if (i > 0) {
          typeString.append(',');
        }
        typeString.append('?');
      }
      typeString.append('>');
      if (typeString.toString().equals(otherType)) {
        return true;
      }
    }
    Element element = declaredType.asElement();
    if (!(element instanceof TypeElement)) {
      return false;
    }
    TypeElement typeElement = (TypeElement) element;
    TypeMirror superType = typeElement.getSuperclass();
    if (isSubtypeOfType(superType, otherType)) {
      return true;
    }
    for (TypeMirror interfaceType : typeElement.getInterfaces()) {
      if (isSubtypeOfType(interfaceType, otherType)) {
        return true;
      }
    }
    return false;
  }
}
