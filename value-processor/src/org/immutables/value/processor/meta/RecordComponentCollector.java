/*
   Copyright 2025 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.value.processor.meta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import org.immutables.value.processor.encode.Instantiator;
import org.immutables.value.processor.encode.Instantiator.InstantiationCreator;
import org.immutables.value.processor.meta.Proto.Protoclass;

final class RecordComponentCollector {
  private final Protoclass protoclass;
  private final ValueType type;
  private final List<ValueAttribute> attributes = Lists.newArrayList();
  private final Styles styles;
  private final Reporter reporter;

  RecordComponentCollector(Protoclass protoclass, ValueType type) {
    this.protoclass = protoclass;
    this.styles = protoclass.styles();
    this.type = type;
    this.reporter = protoclass.report();
  }

  void collect() {
    TypeElement recordType = (TypeElement) protoclass.sourceElement();

    for (ExecutableElement accessor : recordComponentAssessors(recordType)) {
      TypeMirror returnType = accessor.getReturnType();

      Reporter reporter = report(accessor);

      ValueAttribute attribute = new ValueAttribute();
      attribute.isGenerateAbstract = true;
      attribute.reporter = reporter;
      attribute.returnType = returnType;

      attribute.element = accessor;
      String parameterName = accessor.getSimpleName().toString();
      attribute.names = styles.forAccessorWithRaw(parameterName, parameterName);

      attribute.constantDefault = DefaultAnnotations.extractConstantDefault(
          reporter, accessor, returnType);

      if (attribute.constantDefault != null) {
        attribute.isGenerateDefault = true;
      }

      attribute.containingType = type;
      attributes.add(attribute);
    }

    Instantiator encodingInstantiator = protoclass.encodingInstantiator();

    @Nullable InstantiationCreator instantiationCreator =
        encodingInstantiator.creatorFor(recordType);

    for (ValueAttribute attribute : attributes) {
      attribute.initAndValidate(instantiationCreator);
    }

    if (instantiationCreator != null) {
      type.additionalImports(instantiationCreator.imports);
    }

    type.attributes.addAll(attributes);
  }

  // we reflectively access newer annotation processing APIs by still compiling
  // to an older version.
  private List<ExecutableElement> recordComponentAssessors(TypeElement type) {
    if (GET_RECORD_ACCESSOR == null) {
      return Collections.emptyList();
    }

    assert GET_RECORD_COMPONENTS != null;
    type = CachingElements.getDelegate(type);
    List<ExecutableElement> accessors = new ArrayList<>();

    try {
      List<?> components = (List<?>) GET_RECORD_COMPONENTS.invoke(type);

      for (Object c : components) {
        ExecutableElement accessor = (ExecutableElement) GET_RECORD_ACCESSOR.invoke(c);
        accessors.add(accessor);
      }
    } catch (IllegalAccessException
             | InvocationTargetException
             | ClassCastException e) {
      reporter.withElement(type)
          .error("Problem with `TypeElement.getRecordComponents.*.getAccessor`"
              + " from record type mirror, compiler mismatch.\n"
              + Throwables.getStackTraceAsString(e));
    }
    return accessors;
  }

  private Reporter report(Element type) {
    return Reporter.from(protoclass.processing()).withElement(type);
  }

  private static final @Nullable Method GET_RECORD_COMPONENTS;
  private static final @Nullable Method GET_RECORD_ACCESSOR;
  private static final String RECORD_COMPONENT_CLASSNAME = "javax.lang.model.element.RecordComponentElement";
  static {
    @Nullable Method getRecordComponents;
    @Nullable Method getAccessor;

    try {
      Class<?> recordComponentClass = Class.forName(RECORD_COMPONENT_CLASSNAME, true,
          TypeElement.class.getClassLoader());

      getRecordComponents = TypeElement.class.getMethod("getRecordComponents");
      getAccessor = recordComponentClass.getMethod("getAccessor");
    } catch (NoSuchMethodException | ClassNotFoundException e) {
      // records not available in javax.lang.model
      getRecordComponents = null;
      getAccessor = null;
    }
    GET_RECORD_COMPONENTS = getRecordComponents;
    GET_RECORD_ACCESSOR = getAccessor;
  }
}
