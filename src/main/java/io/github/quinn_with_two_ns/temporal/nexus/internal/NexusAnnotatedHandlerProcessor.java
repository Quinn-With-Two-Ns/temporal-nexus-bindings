package io.github.quinn_with_two_ns.temporal.nexus.internal;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;

/** Generates complete Nexus service implementations from annotated Temporal handler methods. */
public final class NexusAnnotatedHandlerProcessor extends AbstractProcessor {
  private static final String PACKAGE = "io.github.quinn_with_two_ns.temporal.nexus.";
  private static final String WORKFLOW = PACKAGE + "WorkflowOperation";
  private static final String SIGNAL = PACKAGE + "SignalOperation";
  private static final String QUERY = PACKAGE + "QueryOperation";
  private static final String UPDATE = PACKAGE + "UpdateOperation";
  private static final String ACTIVITY = PACKAGE + "ActivityOperation";
  private static final String SERVICE_MAPPING = PACKAGE + "ServiceMapping";

  private static final String NEXUS_SERVICE = "io.nexusrpc.Service";
  private static final String NEXUS_OPERATION = "io.nexusrpc.Operation";
  private static final String WORKFLOW_INTERFACE = "io.temporal.workflow.WorkflowInterface";
  private static final String ACTIVITY_INTERFACE = "io.temporal.activity.ActivityInterface";
  private static final String WORKFLOW_METHOD = "io.temporal.workflow.WorkflowMethod";
  private static final String SIGNAL_METHOD = "io.temporal.workflow.SignalMethod";
  private static final String QUERY_METHOD = "io.temporal.workflow.QueryMethod";

  private Elements elements;
  private Types types;
  private Filer filer;
  private Messager messager;
  private ExpressionTypeValidator expressionTypeValidator;
  private boolean generated;

  @Override
  public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
    super.init(environment);
    this.elements = environment.getElementUtils();
    this.types = environment.getTypeUtils();
    this.filer = environment.getFiler();
    this.messager = environment.getMessager();
    this.expressionTypeValidator = new ExpressionTypeValidator(elements, types);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> annotations = operationAnnotationTypes();
    annotations.add(SERVICE_MAPPING);
    return annotations;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.RELEASE_8;
  }

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    if (generated || roundEnvironment.processingOver() || annotations.isEmpty()) {
      return false;
    }
    Map<String, ServiceModel> services = new LinkedHashMap<>();
    boolean valid = validateServiceMappings(roundEnvironment);
    for (String annotationName : operationAnnotationTypes()) {
      @Nullable TypeElement annotation = elements.getTypeElement(annotationName);
      if (annotation == null) {
        continue;
      }
      for (Element element : roundEnvironment.getElementsAnnotatedWith(annotation)) {
        if (element.getKind() != ElementKind.METHOD) {
          error(element, "Nexus operation annotations are only supported on methods");
          valid = false;
          continue;
        }
        @Nullable MappingModel mapping =
            readMapping((ExecutableElement) element, annotationName, services);
        valid &= mapping != null;
      }
    }
    if (!valid) {
      generated = true;
      return false;
    }
    for (ServiceModel service : services.values()) {
      valid &= validateCompleteService(service);
    }
    if (valid) {
      for (ServiceModel service : services.values()) {
        try {
          generateService(service);
        } catch (IOException e) {
          error(service.service, "Failed generating Nexus binding: " + e.getMessage());
          valid = false;
        }
      }
    }
    generated = true;
    return false;
  }

  private Set<String> operationAnnotationTypes() {
    return new HashSet<>(Arrays.asList(WORKFLOW, SIGNAL, QUERY, UPDATE, ACTIVITY));
  }

  private boolean validateServiceMappings(RoundEnvironment roundEnvironment) {
    @Nullable TypeElement mappingAnnotation = elements.getTypeElement(SERVICE_MAPPING);
    if (mappingAnnotation == null) {
      return true;
    }
    boolean valid = true;
    for (Element element : roundEnvironment.getElementsAnnotatedWith(mappingAnnotation)) {
      if (element.getKind() != ElementKind.CLASS
          || !implementsTemporalInterface((TypeElement) element, new HashSet<String>())) {
        error(
            element,
            "@ServiceMapping requires a Temporal workflow or activity implementation class");
        valid = false;
        continue;
      }
      @Nullable TypeMirror serviceType = typeValue(annotation(element, SERVICE_MAPPING), "value");
      if (serviceType == null || serviceType.getKind() != TypeKind.DECLARED) {
        error(element, "@ServiceMapping requires a typed Nexus service");
        valid = false;
        continue;
      }
      TypeElement serviceElement = (TypeElement) ((DeclaredType) serviceType).asElement();
      if (annotation(serviceElement, NEXUS_SERVICE) == null) {
        error(
            element,
            serviceElement.getQualifiedName()
                + " in @ServiceMapping is not annotated with @Service");
        valid = false;
      }
    }
    return valid;
  }

  private @Nullable MappingModel readMapping(
      ExecutableElement method, String annotationName, Map<String, ServiceModel> services) {
    HandlerKind kind = handlerKind(annotationName);
    if (kind == HandlerKind.UPDATE) {
      error(
          method,
          "Nexus update operations require asynchronous update support; TODO for the real implementation");
      return null;
    }
    if (kind == HandlerKind.ACTIVITY) {
      error(
          method,
          "Nexus activity operations require SDK support newer than Temporal Java SDK 1.37.0;"
              + " TODO for the real implementation");
      return null;
    }
    if (!method.getModifiers().contains(Modifier.PUBLIC)) {
      error(method, "Annotated Temporal handler methods must be public");
      return null;
    }
    TypeElement owner = (TypeElement) Objects.requireNonNull(method.getEnclosingElement());
    @Nullable TemporalMethod temporalMethod = findTemporalMethod(owner, method);
    if (temporalMethod == null) {
      error(method, "Nexus annotation is not on a discoverable Temporal handler method");
      return null;
    }
    if (temporalMethod.kind != kind) {
      error(
          method,
          "Nexus annotation kind "
              + kind
              + " does not match Temporal handler kind "
              + temporalMethod.kind);
      return null;
    }

    @Nullable AnnotationMirror annotation = annotation(method, annotationName);
    @Nullable TypeMirror serviceType = typeValue(annotation, "service");
    if (serviceType == null || serviceType.getKind() == TypeKind.VOID) {
      serviceType = defaultService(owner);
    }
    if (serviceType == null || serviceType.getKind() != TypeKind.DECLARED) {
      error(
          method,
          "A typed Nexus service is required; specify service or add @ServiceMapping"
              + " to the Temporal implementation");
      return null;
    }
    TypeElement serviceElement = (TypeElement) ((DeclaredType) serviceType).asElement();
    if (annotation(serviceElement, NEXUS_SERVICE) == null) {
      error(method, serviceElement.getQualifiedName() + " is not annotated with @Service");
      return null;
    }
    @Nullable ServiceModel service = services.get(serviceElement.getQualifiedName().toString());
    if (service == null) {
      service = readService(serviceElement);
      if (service == null) {
        return null;
      }
      services.put(serviceElement.getQualifiedName().toString(), service);
    }

    String operationName = stringValue(annotation, "name");
    @Nullable OperationModel operation = service.operations.get(operationName);
    if (operation == null) {
      error(
          method,
          "Nexus service "
              + serviceElement.getQualifiedName()
              + " has no operation named "
              + operationName);
      return null;
    }
    if (service.mappings.containsKey(operationName)) {
      error(
          method,
          "Duplicate mapping for "
              + serviceElement.getQualifiedName()
              + "."
              + operationName
              + "; also mapped by "
              + service.mappings.get(operationName).source);
      return null;
    }

    String idExpression = stringValue(annotation, "workflowId");
    if (!validExpression(
        method,
        "workflowId",
        idExpression,
        operation.inputType,
        elements.getTypeElement(String.class.getName()).asType())) {
      return null;
    }
    WorkflowStartOptionsModel startOptions = WorkflowStartOptionsModel.EMPTY;
    if (kind == HandlerKind.WORKFLOW) {
      @Nullable AnnotationMirror options = annotationValue(annotation, "options");
      startOptions = readWorkflowStartOptions(method, options, operation.inputType);
      if (startOptions == null) {
        return null;
      }
    }
    List<String> argumentExpressions = stringArrayValue(annotation, "arguments");
    List<? extends VariableElement> parameters = method.getParameters();
    boolean directInput = argumentExpressions.isEmpty() && parameters.size() == 1;
    if (!directInput && argumentExpressions.size() != parameters.size()) {
      error(
          method,
          "Argument expression count "
              + argumentExpressions.size()
              + " does not match Temporal parameter count "
              + parameters.size());
      return null;
    }
    for (int i = 0; i < argumentExpressions.size(); i++) {
      if (!validExpression(
          method,
          "arguments[" + i + "]",
          argumentExpressions.get(i),
          operation.inputType,
          parameters.get(i).asType())) {
        return null;
      }
    }
    if (directInput && !assignable(operation.inputType, boxed(parameters.get(0).asType()))) {
      error(
          method,
          "Nexus input "
              + operation.inputType
              + " is not assignable to Temporal argument "
              + parameters.get(0).asType());
      return null;
    }
    if (!types.isSameType(boxed(method.getReturnType()), operation.outputType)) {
      error(
          method,
          "Temporal output "
              + method.getReturnType()
              + " does not match Nexus output "
              + operation.outputType);
      return null;
    }
    if (kind == HandlerKind.SIGNAL && operation.outputType.getKind() != TypeKind.VOID) {
      error(method, "Signal Nexus operation output must be void");
      return null;
    }

    List<String> parameterClasses = new ArrayList<>();
    for (VariableElement parameter : parameters) {
      parameterClasses.add(classLiteral(parameter.asType()));
    }
    MappingModel mapping =
        new MappingModel(
            kind,
            method,
            operation,
            temporalMethod.temporalName,
            idExpression,
            startOptions,
            argumentExpressions,
            parameterClasses);
    service.mappings.put(operationName, mapping);
    return mapping;
  }

  private @Nullable WorkflowStartOptionsModel readWorkflowStartOptions(
      Element method, @Nullable AnnotationMirror annotation, TypeMirror inputType) {
    WorkflowStartOptionsModel options =
        new WorkflowStartOptionsModel(
            stringValue(annotation, "taskQueue"),
            stringValue(annotation, "executionTimeout"),
            stringValue(annotation, "runTimeout"),
            stringValue(annotation, "taskTimeout"),
            stringValue(annotation, "workflowIdReusePolicy"),
            stringValue(annotation, "workflowIdConflictPolicy"),
            stringValue(annotation, "retryInitialInterval"),
            stringValue(annotation, "retryBackoffCoefficient"),
            stringValue(annotation, "retryMaximumAttempts"),
            stringValue(annotation, "retryMaximumInterval"),
            stringArrayValue(annotation, "retryDoNotRetry"),
            stringValue(annotation, "startDelay"),
            stringValue(annotation, "priorityKey"),
            stringValue(annotation, "priorityFairnessKey"),
            stringValue(annotation, "priorityFairnessWeight"),
            stringValue(annotation, "summary"),
            stringValue(annotation, "details"));
    if (!validWorkflowStartOption(method, "taskQueue", options.taskQueue, inputType)
        || !validWorkflowStartOption(
            method, "executionTimeout", options.executionTimeout, inputType)
        || !validWorkflowStartOption(method, "runTimeout", options.runTimeout, inputType)
        || !validWorkflowStartOption(method, "taskTimeout", options.taskTimeout, inputType)
        || !validWorkflowStartOption(
            method, "workflowIdReusePolicy", options.workflowIdReusePolicy, inputType)
        || !validWorkflowStartOption(
            method, "workflowIdConflictPolicy", options.workflowIdConflictPolicy, inputType)
        || !validWorkflowStartOption(
            method, "retryInitialInterval", options.retryInitialInterval, inputType)
        || !validWorkflowStartOption(
            method, "retryBackoffCoefficient", options.retryBackoffCoefficient, inputType)
        || !validWorkflowStartOption(
            method, "retryMaximumAttempts", options.retryMaximumAttempts, inputType)
        || !validWorkflowStartOption(
            method, "retryMaximumInterval", options.retryMaximumInterval, inputType)
        || !validWorkflowStartOption(method, "startDelay", options.startDelay, inputType)
        || !validWorkflowStartOption(method, "priorityKey", options.priorityKey, inputType)
        || !validWorkflowStartOption(
            method, "priorityFairnessKey", options.priorityFairnessKey, inputType)
        || !validWorkflowStartOption(
            method, "priorityFairnessWeight", options.priorityFairnessWeight, inputType)
        || !validWorkflowStartOption(method, "summary", options.summary, inputType)
        || !validWorkflowStartOption(method, "details", options.details, inputType)) {
      return null;
    }
    for (int i = 0; i < options.retryDoNotRetry.size(); i++) {
      if (!validWorkflowStartOption(
          method, "retryDoNotRetry[" + i + "]", options.retryDoNotRetry.get(i), inputType)) {
        return null;
      }
    }
    if (literal(options.workflowIdReusePolicy) && literal(options.workflowIdConflictPolicy)) {
      String reusePolicy = shortPolicy(options.workflowIdReusePolicy, "WORKFLOW_ID_REUSE_POLICY_");
      String conflictPolicy =
          shortPolicy(options.workflowIdConflictPolicy, "WORKFLOW_ID_CONFLICT_POLICY_");
      if ("TERMINATE_IF_RUNNING".equals(reusePolicy) && !"UNSPECIFIED".equals(conflictPolicy)) {
        error(
            method,
            "Invalid options: workflowIdConflictPolicy cannot be used with"
                + " workflowIdReusePolicy TERMINATE_IF_RUNNING");
        return null;
      }
    }
    return options;
  }

  private boolean validWorkflowStartOption(
      Element element, String name, String source, TypeMirror inputType) {
    if (source.isEmpty()) {
      return true;
    }
    String attribute = "options." + name;
    TypeElement string = elements.getTypeElement(String.class.getName());
    if (string == null
        || !validExpression(element, attribute, source, inputType, string.asType())) {
      return false;
    }
    if (!literal(source)) {
      return true;
    }
    try {
      validateLiteralWorkflowStartOption(attribute, name, source);
      return true;
    } catch (IllegalArgumentException e) {
      error(element, "Invalid " + e.getMessage());
      return false;
    }
  }

  private boolean literal(String source) {
    return !source.isEmpty() && !source.contains("#{");
  }

  private void validateLiteralWorkflowStartOption(String attribute, String name, String value) {
    if ("executionTimeout".equals(name)
        || "runTimeout".equals(name)
        || "taskTimeout".equals(name)
        || "retryInitialInterval".equals(name)
        || "retryMaximumInterval".equals(name)) {
      Duration duration = duration(attribute, value);
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(attribute + " must be greater than zero");
      }
    } else if ("startDelay".equals(name)) {
      if (duration(attribute, value).isNegative()) {
        throw new IllegalArgumentException(attribute + " must not be negative");
      }
    } else if ("workflowIdReusePolicy".equals(name)) {
      String policy = shortPolicy(value, "WORKFLOW_ID_REUSE_POLICY_");
      if (!Arrays.asList(
              "UNSPECIFIED",
              "ALLOW_DUPLICATE",
              "ALLOW_DUPLICATE_FAILED_ONLY",
              "REJECT_DUPLICATE",
              "TERMINATE_IF_RUNNING")
          .contains(policy)) {
        throw new IllegalArgumentException(
            attribute + " is not a recognized workflow ID reuse policy");
      }
    } else if ("workflowIdConflictPolicy".equals(name)) {
      String policy = shortPolicy(value, "WORKFLOW_ID_CONFLICT_POLICY_");
      if (!Arrays.asList("UNSPECIFIED", "FAIL", "USE_EXISTING", "TERMINATE_EXISTING")
          .contains(policy)) {
        throw new IllegalArgumentException(
            attribute + " is not a recognized workflow ID conflict policy");
      }
    } else if ("retryBackoffCoefficient".equals(name)) {
      double coefficient = decimal(attribute, value);
      if (!Double.isFinite(coefficient) || coefficient < 1.0) {
        throw new IllegalArgumentException(
            attribute + " must be a finite number greater than or equal to 1.0");
      }
    } else if ("retryMaximumAttempts".equals(name) || "priorityKey".equals(name)) {
      if (integer(attribute, value) < 0) {
        throw new IllegalArgumentException(attribute + " must not be negative");
      }
    } else if ("priorityFairnessWeight".equals(name)) {
      double weight = decimal(attribute, value);
      if (!Double.isFinite(weight) || weight < 0.0) {
        throw new IllegalArgumentException(attribute + " must be a finite, non-negative number");
      }
    }
  }

  private Duration duration(String attribute, String value) {
    try {
      return Duration.parse(value);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(attribute + " must be an ISO-8601 duration", e);
    }
  }

  private int integer(String attribute, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(attribute + " must be an integer", e);
    }
  }

  private double decimal(String attribute, String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(attribute + " must be a number", e);
    }
  }

  private String shortPolicy(String value, String prefix) {
    String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
  }

  private @Nullable ServiceModel readService(TypeElement service) {
    Map<String, OperationModel> operations = new LinkedHashMap<>();
    Set<String> methodNames = new HashSet<>();
    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(service))) {
      if (Objects.requireNonNull(method.getEnclosingElement())
          .toString()
          .equals(Object.class.getName())) {
        continue;
      }
      @Nullable AnnotationMirror operationAnnotation = annotation(method, NEXUS_OPERATION);
      if (operationAnnotation == null) {
        continue;
      }
      String operationName = stringValue(operationAnnotation, "name");
      if (operationName.isEmpty()) {
        operationName = method.getSimpleName().toString();
      }
      if (operations.containsKey(operationName)) {
        error(method, "Duplicate Nexus operation name " + operationName);
        return null;
      }
      if (!methodNames.add(method.getSimpleName().toString())) {
        error(method, "Overloaded Nexus service methods cannot generate @OperationImpl methods");
        return null;
      }
      if (method.getParameters().size() > 1) {
        error(method, "Nexus operations may have at most one input parameter");
        return null;
      }
      TypeMirror input =
          method.getParameters().isEmpty()
              ? types.getNoType(TypeKind.VOID)
              : boxed(method.getParameters().get(0).asType());
      TypeMirror output = boxed(method.getReturnType());
      operations.put(
          operationName,
          new OperationModel(operationName, method.getSimpleName().toString(), input, output));
    }
    if (operations.isEmpty()) {
      error(service, "Typed Nexus service has no @Operation methods");
      return null;
    }
    PackageElement servicePackage = elements.getPackageOf(service);
    return new ServiceModel(
        service,
        servicePackage.getQualifiedName().toString(),
        service.getSimpleName() + "NexusBindings",
        operations);
  }

  private boolean validateCompleteService(ServiceModel service) {
    List<String> missing = new ArrayList<>(service.operations.keySet());
    missing.removeAll(service.mappings.keySet());
    if (!missing.isEmpty()) {
      Collections.sort(missing);
      error(service.service, "Incomplete Nexus service; missing operations: " + missing);
      return false;
    }
    return true;
  }

  private void generateService(ServiceModel service) throws IOException {
    String qualifiedName =
        service.packageName.isEmpty()
            ? service.generatedClassName
            : service.packageName + "." + service.generatedClassName;
    List<Element> origins = new ArrayList<>();
    origins.add(service.service);
    for (MappingModel mapping : service.mappings.values()) {
      origins.add(mapping.source);
    }
    JavaFileObject sourceFile =
        filer.createSourceFile(qualifiedName, origins.toArray(new Element[0]));
    try (Writer writer = sourceFile.openWriter()) {
      if (!service.packageName.isEmpty()) {
        writer.write("package " + service.packageName + ";\n\n");
      }
      writer.write(
          "/** Generated Nexus binding for {@code "
              + javadocText(service.service.getQualifiedName().toString())
              + "}. */\n");
      writer.write(
          "@"
              + generatedAnnotation()
              + "(value = "
              + quote(NexusAnnotatedHandlerProcessor.class.getName())
              + ")\n");
      writer.write(
          "@io.nexusrpc.handler.ServiceImpl(service = "
              + service.service.getQualifiedName()
              + ".class)\n");
      writer.write("public final class " + service.generatedClassName + " {\n");
      writer.write("  private " + service.generatedClassName + "() {}\n\n");
      writer.write(
          "  /** Creates this generated Nexus binding. */\n"
              + "  public static "
              + service.generatedClassName
              + " create() {\n"
              + "    return new "
              + service.generatedClassName
              + "();\n"
              + "  }\n");
      writer.write(
          "\n  /**\n"
              + "   * Creates and registers this generated Nexus binding.\n"
              + "   *\n"
              + "   * @param worker worker that will expose the typed Nexus service\n"
              + "   * @return the registered binding\n"
              + "   */\n"
              + "  public static "
              + service.generatedClassName
              + " register(io.temporal.worker.Worker worker) {\n"
              + "    "
              + service.generatedClassName
              + " binding = create();\n"
              + "    worker.registerNexusServiceImplementation(binding);\n"
              + "    return binding;\n"
              + "  }\n");

      List<MappingModel> mappings = new ArrayList<>(service.mappings.values());
      Collections.sort(mappings, Comparator.comparing(mapping -> mapping.operation.methodName));
      for (MappingModel mapping : mappings) {
        generateOperation(writer, service, mapping);
      }
      writer.write("}\n");
    }
  }

  private void generateOperation(Writer writer, ServiceModel service, MappingModel mapping)
      throws IOException {
    String inputType = sourceType(mapping.operation.inputType);
    String outputType = sourceType(mapping.operation.outputType);
    String nexusOperation = service.service.getQualifiedName() + "#" + mapping.operation.methodName;
    String temporalHandler =
        mapping.source.getEnclosingElement() + "#" + mapping.source.getSimpleName();
    writer.write(
        "\n  /** Binds Nexus operation {@code "
            + javadocText(nexusOperation)
            + "} to Temporal handler {@code "
            + javadocText(temporalHandler)
            + "}. */\n");
    writer.write("  @io.nexusrpc.handler.OperationImpl\n");
    writer.write(
        "  public io.nexusrpc.handler.OperationHandler<"
            + inputType
            + ", "
            + outputType
            + "> "
            + mapping.operation.methodName
            + "() {\n");
    writer.write(
        "    return io.github.quinn_with_two_ns.temporal.nexus.internal.GeneratedNexusOperationHandlers."
            + factoryMethod(mapping.kind)
            + "(\n");
    writer.write("        " + service.service.getQualifiedName() + ".class,\n");
    writer.write("        " + quote(mapping.operation.name) + ",\n");
    writer.write("        " + quote(mapping.temporalName) + ",\n");
    writer.write("        " + quote(mapping.idExpression) + ",\n");
    if (mapping.kind == HandlerKind.WORKFLOW) {
      writer.write("        " + workflowStartOptions(mapping.startOptions) + ",\n");
    }
    writer.write("        " + stringArray(mapping.argumentExpressions) + ",\n");
    writer.write("        " + classArray(mapping.parameterClasses));
    writer.write(");\n");
    writer.write("  }\n");
  }

  private @Nullable TemporalMethod findTemporalMethod(TypeElement owner, ExecutableElement method) {
    Set<String> visited = new HashSet<>();
    @Nullable TypeElement current = owner;
    while (current != null) {
      for (TypeMirror implementedType : current.getInterfaces()) {
        @Nullable TemporalMethod temporalMethod =
            findTemporalMethod((DeclaredType) implementedType, method, visited);
        if (temporalMethod != null) {
          return temporalMethod;
        }
      }
      TypeMirror superclass = current.getSuperclass();
      if (superclass.getKind() != TypeKind.DECLARED) {
        break;
      }
      TypeElement superclassElement = (TypeElement) ((DeclaredType) superclass).asElement();
      if (superclassElement.getQualifiedName().contentEquals(Object.class.getName())) {
        break;
      }
      current = superclassElement;
    }
    return null;
  }

  private boolean implementsTemporalInterface(TypeElement type, Set<String> visited) {
    if (!visited.add(type.getQualifiedName().toString())) {
      return false;
    }
    for (TypeMirror supertype : types.directSupertypes(type.asType())) {
      if (supertype.getKind() != TypeKind.DECLARED) {
        continue;
      }
      TypeElement superElement = (TypeElement) ((DeclaredType) supertype).asElement();
      if (annotation(superElement, WORKFLOW_INTERFACE) != null
          || annotation(superElement, ACTIVITY_INTERFACE) != null
          || implementsTemporalInterface(superElement, visited)) {
        return true;
      }
    }
    return false;
  }

  private @Nullable TemporalMethod findTemporalMethod(
      DeclaredType interfaceType, ExecutableElement method, Set<String> visited) {
    TypeElement interfaceElement = (TypeElement) interfaceType.asElement();
    if (!visited.add(interfaceType.toString())) {
      return null;
    }
    for (ExecutableElement candidate :
        ElementFilter.methodsIn(interfaceElement.getEnclosedElements())) {
      if (!sameSignature(method, candidate, interfaceType)) {
        continue;
      }
      @Nullable TemporalMethod temporalMethod = temporalMethod(candidate);
      if (temporalMethod != null) {
        return temporalMethod;
      }
    }
    for (TypeMirror parent : types.directSupertypes(interfaceType)) {
      if (parent.getKind() != TypeKind.DECLARED) {
        continue;
      }
      @Nullable TemporalMethod temporalMethod =
          findTemporalMethod((DeclaredType) parent, method, visited);
      if (temporalMethod != null) {
        return temporalMethod;
      }
    }
    return null;
  }

  private @Nullable TypeMirror defaultService(TypeElement temporalImplementation) {
    @Nullable AnnotationMirror mapping = annotation(temporalImplementation, SERVICE_MAPPING);
    return mapping == null ? null : typeValue(mapping, "value");
  }

  private @Nullable TemporalMethod temporalMethod(ExecutableElement candidate) {
    @Nullable AnnotationMirror workflow = annotation(candidate, WORKFLOW_METHOD);
    if (workflow != null) {
      String name = stringValue(workflow, "name");
      if (name.isEmpty()) {
        name = Objects.requireNonNull(candidate.getEnclosingElement()).getSimpleName().toString();
      }
      return new TemporalMethod(HandlerKind.WORKFLOW, name);
    }
    @Nullable AnnotationMirror signal = annotation(candidate, SIGNAL_METHOD);
    if (signal != null) {
      return new TemporalMethod(HandlerKind.SIGNAL, defaultMethodName(signal, candidate));
    }
    @Nullable AnnotationMirror query = annotation(candidate, QUERY_METHOD);
    if (query != null) {
      return new TemporalMethod(HandlerKind.QUERY, defaultMethodName(query, candidate));
    }
    return null;
  }

  private boolean sameSignature(
      ExecutableElement implementation,
      ExecutableElement declaration,
      DeclaredType declaringInterface) {
    if (!implementation.getSimpleName().contentEquals(declaration.getSimpleName())) {
      return false;
    }
    List<? extends VariableElement> implementationParameters = implementation.getParameters();
    List<? extends TypeMirror> declarationParameters =
        ((ExecutableType) types.asMemberOf(declaringInterface, declaration)).getParameterTypes();
    if (implementationParameters.size() != declarationParameters.size()) {
      return false;
    }
    for (int i = 0; i < implementationParameters.size(); i++) {
      if (!types.isSameType(
          types.erasure(implementationParameters.get(i).asType()),
          types.erasure(declarationParameters.get(i)))) {
        return false;
      }
    }
    return true;
  }

  private String defaultMethodName(AnnotationMirror annotation, ExecutableElement method) {
    String name = stringValue(annotation, "name");
    return name.isEmpty() ? method.getSimpleName().toString() : name;
  }

  private boolean validExpression(
      Element element,
      String attribute,
      @Nullable String source,
      TypeMirror inputType,
      TypeMirror targetType) {
    if (source == null || source.trim().isEmpty()) {
      error(element, attribute + " expression is required");
      return false;
    }
    try {
      ExpressionModel expression = ExpressionModel.parse(source);
      expressionTypeValidator.validate(expression, inputType, targetType);
      return true;
    } catch (IllegalArgumentException e) {
      error(element, "Invalid " + attribute + " expression: " + e.getMessage());
      return false;
    }
  }

  private @Nullable AnnotationMirror annotation(Element element, String qualifiedName) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      if (annotation.getAnnotationType().toString().equals(qualifiedName)) {
        return annotation;
      }
    }
    return null;
  }

  private Map<String, AnnotationValue> values(@Nullable AnnotationMirror annotation) {
    Map<String, AnnotationValue> result = new HashMap<>();
    if (annotation == null) {
      return result;
    }
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elements.getElementValuesWithDefaults(annotation).entrySet()) {
      result.put(entry.getKey().getSimpleName().toString(), entry.getValue());
    }
    return result;
  }

  private String stringValue(@Nullable AnnotationMirror annotation, String name) {
    @Nullable AnnotationValue value = values(annotation).get(name);
    return value == null ? "" : (String) value.getValue();
  }

  private @Nullable AnnotationMirror annotationValue(
      @Nullable AnnotationMirror annotation, String name) {
    @Nullable AnnotationValue value = values(annotation).get(name);
    return value == null ? null : (AnnotationMirror) value.getValue();
  }

  private @Nullable TypeMirror typeValue(@Nullable AnnotationMirror annotation, String name) {
    @Nullable AnnotationValue value = values(annotation).get(name);
    return value == null ? null : (TypeMirror) value.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<String> stringArrayValue(@Nullable AnnotationMirror annotation, String name) {
    @Nullable AnnotationValue value = values(annotation).get(name);
    if (value == null) {
      return new ArrayList<>();
    }
    List<? extends AnnotationValue> array = (List<? extends AnnotationValue>) value.getValue();
    List<String> result = new ArrayList<>();
    for (AnnotationValue item : array) {
      result.add((String) item.getValue());
    }
    return result;
  }

  private TypeMirror boxed(TypeMirror type) {
    if (type.getKind() == TypeKind.VOID) {
      return type;
    }
    if (type.getKind().isPrimitive()) {
      return types.boxedClass((PrimitiveType) type).asType();
    }
    return type;
  }

  private boolean assignable(TypeMirror from, TypeMirror to) {
    return types.isAssignable(from, to) || (isNumber(from) && isNumber(to));
  }

  private boolean isNumber(TypeMirror type) {
    TypeElement number = elements.getTypeElement(Number.class.getName());
    return number != null && types.isAssignable(type, number.asType());
  }

  private String sourceType(TypeMirror type) {
    if (type.getKind() == TypeKind.VOID) {
      return Void.class.getCanonicalName();
    }
    return type.toString();
  }

  private String classLiteral(TypeMirror type) {
    TypeMirror boxed = boxed(type);
    if (boxed.getKind() == TypeKind.ARRAY) {
      return boxed.toString() + ".class";
    }
    return types.erasure(boxed).toString() + ".class";
  }

  private String factoryMethod(HandlerKind kind) {
    switch (kind) {
      case WORKFLOW:
        return "workflow";
      case SIGNAL:
        return "signal";
      case QUERY:
        return "query";
      default:
        throw new IllegalArgumentException("Unsupported handler kind " + kind);
    }
  }

  private HandlerKind handlerKind(String annotationName) {
    if (WORKFLOW.equals(annotationName)) return HandlerKind.WORKFLOW;
    if (SIGNAL.equals(annotationName)) return HandlerKind.SIGNAL;
    if (QUERY.equals(annotationName)) return HandlerKind.QUERY;
    if (UPDATE.equals(annotationName)) return HandlerKind.UPDATE;
    if (ACTIVITY.equals(annotationName)) return HandlerKind.ACTIVITY;
    throw new IllegalArgumentException("Unknown annotation " + annotationName);
  }

  private String stringArray(List<String> values) {
    StringBuilder result = new StringBuilder("new String[] {");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) result.append(", ");
      result.append(quote(values.get(i)));
    }
    return result.append('}').toString();
  }

  private String classArray(List<String> values) {
    StringBuilder result = new StringBuilder("new Class<?>[] {");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) result.append(", ");
      result.append(values.get(i));
    }
    return result.append('}').toString();
  }

  private String workflowStartOptions(WorkflowStartOptionsModel options) {
    return "new "
        + GeneratedNexusOperationHandlers.class.getCanonicalName()
        + ".WorkflowStartOptionsSpec("
        + quote(options.taskQueue)
        + ", "
        + quote(options.executionTimeout)
        + ", "
        + quote(options.runTimeout)
        + ", "
        + quote(options.taskTimeout)
        + ", "
        + quote(options.workflowIdReusePolicy)
        + ", "
        + quote(options.workflowIdConflictPolicy)
        + ", "
        + quote(options.retryInitialInterval)
        + ", "
        + quote(options.retryBackoffCoefficient)
        + ", "
        + quote(options.retryMaximumAttempts)
        + ", "
        + quote(options.retryMaximumInterval)
        + ", "
        + stringArray(options.retryDoNotRetry)
        + ", "
        + quote(options.startDelay)
        + ", "
        + quote(options.priorityKey)
        + ", "
        + quote(options.priorityFairnessKey)
        + ", "
        + quote(options.priorityFairnessWeight)
        + ", "
        + quote(options.summary)
        + ", "
        + quote(options.details)
        + ")";
  }

  private String quote(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        + "\"";
  }

  private String generatedAnnotation() {
    return processingEnv.getSourceVersion().compareTo(SourceVersion.RELEASE_8) > 0
        ? "javax.annotation.processing.Generated"
        : "javax.annotation.Generated";
  }

  private String javadocText(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("*/", "*&#47;");
  }

  private void error(Element element, String message) {
    messager.printMessage(Diagnostic.Kind.ERROR, message, element);
  }

  private enum HandlerKind {
    WORKFLOW,
    SIGNAL,
    QUERY,
    UPDATE,
    ACTIVITY
  }

  private static final class TemporalMethod {
    private final HandlerKind kind;
    private final String temporalName;

    private TemporalMethod(HandlerKind kind, String temporalName) {
      this.kind = kind;
      this.temporalName = temporalName;
    }
  }

  private static final class OperationModel {
    private final String name;
    private final String methodName;
    private final TypeMirror inputType;
    private final TypeMirror outputType;

    private OperationModel(
        String name, String methodName, TypeMirror inputType, TypeMirror outputType) {
      this.name = name;
      this.methodName = methodName;
      this.inputType = inputType;
      this.outputType = outputType;
    }
  }

  private static final class WorkflowStartOptionsModel {
    private static final WorkflowStartOptionsModel EMPTY =
        new WorkflowStartOptionsModel(
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            Collections.<String>emptyList(),
            "",
            "",
            "",
            "",
            "",
            "");

    private final String taskQueue;
    private final String executionTimeout;
    private final String runTimeout;
    private final String taskTimeout;
    private final String workflowIdReusePolicy;
    private final String workflowIdConflictPolicy;
    private final String retryInitialInterval;
    private final String retryBackoffCoefficient;
    private final String retryMaximumAttempts;
    private final String retryMaximumInterval;
    private final List<String> retryDoNotRetry;
    private final String startDelay;
    private final String priorityKey;
    private final String priorityFairnessKey;
    private final String priorityFairnessWeight;
    private final String summary;
    private final String details;

    private WorkflowStartOptionsModel(
        String taskQueue,
        String executionTimeout,
        String runTimeout,
        String taskTimeout,
        String workflowIdReusePolicy,
        String workflowIdConflictPolicy,
        String retryInitialInterval,
        String retryBackoffCoefficient,
        String retryMaximumAttempts,
        String retryMaximumInterval,
        List<String> retryDoNotRetry,
        String startDelay,
        String priorityKey,
        String priorityFairnessKey,
        String priorityFairnessWeight,
        String summary,
        String details) {
      this.taskQueue = taskQueue;
      this.executionTimeout = executionTimeout;
      this.runTimeout = runTimeout;
      this.taskTimeout = taskTimeout;
      this.workflowIdReusePolicy = workflowIdReusePolicy;
      this.workflowIdConflictPolicy = workflowIdConflictPolicy;
      this.retryInitialInterval = retryInitialInterval;
      this.retryBackoffCoefficient = retryBackoffCoefficient;
      this.retryMaximumAttempts = retryMaximumAttempts;
      this.retryMaximumInterval = retryMaximumInterval;
      this.retryDoNotRetry = retryDoNotRetry;
      this.startDelay = startDelay;
      this.priorityKey = priorityKey;
      this.priorityFairnessKey = priorityFairnessKey;
      this.priorityFairnessWeight = priorityFairnessWeight;
      this.summary = summary;
      this.details = details;
    }
  }

  private static final class MappingModel {
    private final HandlerKind kind;
    private final ExecutableElement source;
    private final OperationModel operation;
    private final String temporalName;
    private final String idExpression;
    private final WorkflowStartOptionsModel startOptions;
    private final List<String> argumentExpressions;
    private final List<String> parameterClasses;

    private MappingModel(
        HandlerKind kind,
        ExecutableElement source,
        OperationModel operation,
        String temporalName,
        String idExpression,
        WorkflowStartOptionsModel startOptions,
        List<String> argumentExpressions,
        List<String> parameterClasses) {
      this.kind = kind;
      this.source = source;
      this.operation = operation;
      this.temporalName = temporalName;
      this.idExpression = idExpression;
      this.startOptions = startOptions;
      this.argumentExpressions = argumentExpressions;
      this.parameterClasses = parameterClasses;
    }
  }

  private static final class ServiceModel {
    private final TypeElement service;
    private final String packageName;
    private final String generatedClassName;
    private final Map<String, OperationModel> operations;
    private final Map<String, MappingModel> mappings = new LinkedHashMap<>();

    private ServiceModel(
        TypeElement service,
        String packageName,
        String generatedClassName,
        Map<String, OperationModel> operations) {
      this.service = service;
      this.packageName = packageName;
      this.generatedClassName = generatedClassName;
      this.operations = operations;
    }
  }
}
