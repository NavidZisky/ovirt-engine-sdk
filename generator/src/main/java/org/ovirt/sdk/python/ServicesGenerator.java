/*
Copyright (c) 2016 Red Hat, Inc.

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

package org.ovirt.sdk.python;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javax.inject.Inject;

import org.ovirt.api.metamodel.concepts.Concept;
import org.ovirt.api.metamodel.concepts.ListType;
import org.ovirt.api.metamodel.concepts.Locator;
import org.ovirt.api.metamodel.concepts.Method;
import org.ovirt.api.metamodel.concepts.Model;
import org.ovirt.api.metamodel.concepts.Name;
import org.ovirt.api.metamodel.concepts.NameParser;
import org.ovirt.api.metamodel.concepts.Parameter;
import org.ovirt.api.metamodel.concepts.PrimitiveType;
import org.ovirt.api.metamodel.concepts.Service;
import org.ovirt.api.metamodel.concepts.StructType;
import org.ovirt.api.metamodel.concepts.Type;
import org.ovirt.api.metamodel.tool.SchemaNames;

/**
 * This class is responsible for generating the classes that represent the services of the model.
 */
public class ServicesGenerator implements PythonGenerator {
    // Well known method names:
    private static final Name ADD = NameParser.parseUsingCase("Add");
    private static final Name GET = NameParser.parseUsingCase("Get");
    private static final Name LIST = NameParser.parseUsingCase("List");
    private static final Name REMOVE = NameParser.parseUsingCase("Remove");
    private static final Name UPDATE = NameParser.parseUsingCase("Update");

    // The directory were the output will be generated:
    protected File out;

    // Reference to the objects used to generate the code:
    @Inject private PythonNames pythonNames;
    @Inject private SchemaNames schemaNames;

    // The buffer used to generate the code:
    private PythonBuffer buffer;

    /**
     * Set the directory were the output will be generated.
     */
    public void setOut(File newOut) {
        out = newOut;
    }

    public void generate(Model model) {
        // Prepare the buffer:
        buffer = new PythonBuffer();
        buffer.setModuleName(pythonNames.getServicesModuleName());

        // Generate the code:
        generateServices(model);

        // Write the file:
        try {
            buffer.write(out);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Error writing services module", exception);
        }
    }

    private void generateServices(Model model) {
        // Generate the imports:
        String rootModuleName = pythonNames.getRootModuleName();
        buffer.addImport("import io");
        buffer.addImport("from %1$s import Error", rootModuleName);
        buffer.addImport("from %1$s import http", rootModuleName);
        buffer.addImport("from %1$s import readers", rootModuleName);
        buffer.addImport("from %1$s import types", rootModuleName);
        buffer.addImport("from %1$s import writers", rootModuleName);
        buffer.addImport("from %1$s import xml", rootModuleName);
        buffer.addImport("from %1$s.service import Service", rootModuleName);
        buffer.addImport("from %1$s.writer import Writer", rootModuleName);

        // The declarations of the services need to appear in inheritance order, otherwise some symbols won't be
        // defined and that will produce errors. To order them correctly we need first to sort them by name, and
        // then sort again so that bases are before extensions.
        Deque<Service> pending = model.services()
            .sorted()
            .collect(toCollection(ArrayDeque::new));
        Deque<Service> sorted = new ArrayDeque<>(pending.size());
        while (!pending.isEmpty()) {
            Service current = pending.removeFirst();
            Service base = current.getBase();
            if (base == null || sorted.contains(base)) {
                sorted.addLast(current);
            }
            else {
                pending.addLast(current);
            }
        }
        sorted.forEach(this::generateService);
    }

    private void generateService(Service service) {
        // Begin class:
        PythonClassName serviceName = pythonNames.getServiceName(service);
        Service base = service.getBase();
        PythonClassName baseName = base != null? pythonNames.getServiceName(base): pythonNames.getBaseServiceName();
        buffer.addLine("class %1$s(%2$s):", serviceName.getClassName(), baseName.getClassName());
        buffer.startBlock();
        generateDoc(service);
        buffer.addLine();

        // Generate the constructor:
        buffer.addLine("def __init__(self, connection, path):");
        buffer.startBlock();
        buffer.addLine("super(%1$s, self).__init__(connection, path)", serviceName.getClassName());
        service.locators().sorted().forEach(this::generateLocatorMember);
        buffer.endBlock();
        buffer.addLine();

        // Generate the methods and locators:
        service.methods().sorted().forEach(this::generateMethod);
        service.locators().sorted().forEach(this::generateLocatorMethod);
        generatePathLocator(service);

        // Generate other methods that don't correspond to model methods or locators:
        generateStr(service);

        // End class:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateMethod(Method method) {
        Name name = method.getName();
        if (ADD.equals(name)) {
            generateAddHttpPost(method);
        }
        else if (GET.equals(name) || LIST.equals(name)) {
            generateHttpGet(method);
        }
        else if (REMOVE.equals(name)) {
            generateHttpDelete(method);
        }
        else if (UPDATE.equals(name)) {
            generateHttpPut(method);
        }
        else {
            generateActionHttpPost(method);
        }
    }

    private void generateAddHttpPost(Method method) {
        // Get the parameters:
        Parameter primaryParameter = getFirstParameter(method);
        List<Parameter> secondaryParameters = getSecondaryParameters(method);

        // Begin method:
        Name methodName = method.getName();
        Name primaryParameterName = primaryParameter.getName();
        String primaryArg = pythonNames.getMemberStyleName(primaryParameterName);
        buffer.addLine("def %1$s(", pythonNames.getMemberStyleName(methodName));
        buffer.startBlock();
        buffer.addLine("self,");
        buffer.addLine("%1$s,", primaryArg);
        secondaryParameters.forEach(this::generateFormalParameter);
        buffer.addLine("headers=None,");
        buffer.addLine("query=None,");
        buffer.endBlock();
        buffer.addLine("):");
        buffer.startBlock();
        generateActionDoc(method, (Parameter p) -> p.isIn() && p.isOut());
        buffer.endBlock();

        // Start body:
        buffer.startBlock();

        // Generate the code to check the type of the parameters:
        buffer.addLine("# Check the types of the parameters:");
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        generateCheckTypeTuple(primaryParameter);
        secondaryParameters.forEach(this::generateCheckTypeTuple);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine();

        // Generate the code to build the URL query:
        buffer.addLine("# Build the URL:");
        buffer.addLine("query = query or {}");
        secondaryParameters.forEach(this::generateUrlParameter);
        buffer.addLine();

        // Generate the code to build the headers:
        buffer.addLine("# Populate the headers:");
        buffer.addLine("headers = headers or {}");
        buffer.addLine();

        // Generate the code to send the request and wait for the response:
        buffer.addLine("# Send the request and wait for the response:");
        buffer.addLine("request = http.Request(method='POST', path=self._path, query=query, headers=headers)");
        generateWriteRequestBody(primaryParameter, primaryArg);
        buffer.addLine("response = self._connection.send(request)");
        buffer.addLine("if response.code in [200, 201, 202]:");
        buffer.startBlock();
        generateReturnResponseBody(primaryParameter);
        buffer.endBlock();
        buffer.addLine("else:");
        buffer.startBlock();
        buffer.addLine("self._check_fault(response)");
        buffer.endBlock();

        // End body:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateActionHttpPost(Method method) {
        // Get the input parameters:
        List<Parameter> inParameters = method.parameters()
            .filter(Parameter::isIn)
            .sorted()
            .collect(toList());

        // Begin method:
        Name name = method.getName();
        buffer.addLine("def %1$s(", pythonNames.getMemberStyleName(name));
        buffer.startBlock();
        buffer.addLine("self,");
        inParameters.forEach(this::generateFormalParameter);
        buffer.addLine("headers=None,");
        buffer.addLine("query=None,");
        buffer.endBlock();
        buffer.addLine("):");

        // Start body:
        buffer.startBlock();
        generateActionDoc(method, Parameter::isIn);

        // Generate the code to check type types of the parameters:
        buffer.addLine("# Check the types of the parameters:");
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        inParameters.forEach(this::generateCheckTypeTuple);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine();

        // Generate the code to populate the action:
        buffer.addLine("# Populate the action:");
        buffer.addLine("action = types.Action(");
        buffer.startBlock();
        inParameters.forEach(this::generateSetActionAttribute);
        buffer.endBlock();
        buffer.addLine(")");
        buffer.addLine();

        // Generate the code to build the request body:
        buffer.addLine("# Build the request body:");
        buffer.addLine("buf = io.BytesIO()");
        buffer.addLine("writer = xml.XmlWriter(buf, indent=True)");
        buffer.addLine("writers.ActionWriter.write_one(action, writer)");
        buffer.addLine("writer.flush()");
        buffer.addLine("body = buf.getvalue()");
        buffer.addLine("writer.close()");
        buffer.addLine("buf.close()");
        buffer.addLine();

        // Generate the code to send the request and wait for the response:
        buffer.addLine("# Send the request and wait for the response:");
        buffer.addLine("request = http.Request(");
        buffer.startBlock();
        buffer.addLine("method='POST',");
        buffer.addLine("path='%%s/%%s' %% (self._path, '%1$s'),", getPath(name));
        buffer.addLine("body=body,");
        buffer.addLine("query=query,");
        buffer.addLine("headers=headers,");
        buffer.endBlock();
        buffer.addLine(")");
        buffer.addLine("response = self._connection.send(request)");
        generateActionResponse(method);

        // End method:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateActionResponse(Method method) {
        Parameter parameter = method.parameters()
            .filter(Parameter::isOut)
            .findFirst()
            .orElse(null);

        if (parameter == null) {
            buffer.addLine("self._check_action(response)");
        }
        else {
            buffer.addLine("action = self._check_action(response)");
            buffer.addLine("return action.%1$s", pythonNames.getMemberStyleName(parameter.getName()));
        }
    }

    private void generateHttpGet(Method method) {
        // Get the input parameters:
        List<Parameter> inParameters = method.parameters()
            .filter(Parameter::isIn)
            .sorted()
            .collect(toList());

        // Get the output parameter:
        Parameter outParameter = method.parameters()
            .filter(Parameter::isOut)
            .findFirst()
            .orElse(null);

        // Begin method:
        Name methodName = method.getName();
        buffer.addLine("def %1$s(", pythonNames.getMemberStyleName(methodName));
        buffer.startBlock();
        buffer.addLine("self,");
        inParameters.forEach(this::generateFormalParameter);
        buffer.addLine("headers=None,");
        buffer.addLine("query=None,");
        buffer.endBlock();
        buffer.addLine("):");

        // Start body:
        buffer.startBlock();
        generateActionDoc(method, Parameter::isIn);

        // Generate the code to check the types of the input parameters:
        buffer.addLine("# Check the types of the parameters:");
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        inParameters.forEach(this::generateCheckTypeTuple);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine();

        // Generate the code to build the URL query:
        buffer.addLine("# Build the URL:");
        buffer.addLine("query = query or {}");
        inParameters.forEach(this::generateUrlParameter);
        buffer.addLine();

        // Generate the code to build the headers:
        buffer.addLine("# Populate the headers:");
        buffer.addLine("headers = headers or {}");
        buffer.addLine();

        // Generate the code to send the request and wait for the response:
        buffer.addLine("# Send the request and wait for the response:");
        buffer.addLine("request = http.Request(method='GET', path=self._path, query=query, headers=headers)");
        buffer.addLine("response = self._connection.send(request)");
        buffer.addLine("if response.code in [200]:");
        buffer.startBlock();
        generateReturnResponseBody(outParameter);
        buffer.endBlock();
        buffer.addLine("else:");
        buffer.startBlock();
        buffer.addLine("self._check_fault(response)");
        buffer.endBlock();

        // End body:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateHttpPut(Method method) {
        // Classify the parameters:
        Parameter primaryParameter = getFirstParameter(method);
        List<Parameter> secondaryParameters = getSecondaryParameters(method);

        // Begin method:
        Name methodName = method.getName();
        Name primaryParameterName = primaryParameter.getName();
        String primaryArg = pythonNames.getMemberStyleName(primaryParameterName);
        buffer.addLine("def %1$s(", pythonNames.getMemberStyleName(methodName));
        buffer.startBlock();
        buffer.addLine("self,");
        buffer.addLine("%1$s,", primaryArg);
        getSecondaryParameters(method).forEach(this::generateFormalParameter);
        buffer.addLine("headers=None,");
        buffer.addLine("query=None,");
        buffer.endBlock();
        buffer.addLine("):");

        // Start body:
        buffer.startBlock();
        generateActionDoc(method, (Parameter p) -> p.isIn() && p.isOut());

        // Generate the code to check the types of the input parameters:
        buffer.addLine("# Check the types of the parameters:");
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        generateCheckTypeTuple(primaryParameter);
        secondaryParameters.forEach(this::generateCheckTypeTuple);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine();

        // Generate the code to build the URL query:
        buffer.addLine("# Build the URL:");
        buffer.addLine("query = query or {}");
        secondaryParameters.forEach(this::generateUrlParameter);
        buffer.addLine();

        // Generate the code to build the headers:
        buffer.addLine("# Populate the headers:");
        buffer.addLine("headers = headers or {}");
        buffer.addLine();

        // Body:
        buffer.addLine("request = http.Request(method='PUT', path=self._path, query=query, headers=headers)");
        generateWriteRequestBody(primaryParameter, primaryArg);
        buffer.addLine("response = self._connection.send(request)");
        buffer.addLine("if response.code in [200]:");
        buffer.startBlock();
        generateReturnResponseBody(primaryParameter);
        buffer.endBlock();
        buffer.addLine("else:");
        buffer.startBlock();
        buffer.addLine("self._check_fault(response)");
        buffer.endBlock();

        // End body:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateWriteRequestBody(Parameter parameter, String variable) {
        Type type = parameter.getType();
        buffer.addLine("buf = None");
        buffer.addLine("writer = None");
        buffer.addLine("try:");
        buffer.startBlock();
        buffer.addLine("buf = io.BytesIO()");
        buffer.addLine("writer = xml.XmlWriter(buf, indent=True)");
        if (type instanceof StructType) {
            PythonClassName writer = pythonNames.getWriterName(type);
            buffer.addLine("writers.%1$s.write_one(%2$s, writer)", writer.getClassName(), variable);
        }
        else if (type instanceof ListType) {
            ListType listType = (ListType) type;
            Type elementType = listType.getElementType();
            PythonClassName writer = pythonNames.getWriterName(elementType);
            buffer.addLine("writers.%1$s.write_many(%2$s, writer)", writer.getClassName(), variable);
        }
        buffer.addLine("writer.flush()");
        buffer.addLine("request.body = buf.getvalue()");
        buffer.endBlock();
        buffer.addLine("finally:");
        buffer.startBlock();
        buffer.addLine("if writer is not None:");
        buffer.startBlock();
        buffer.addLine("writer.close()");
        buffer.endBlock();
        buffer.addLine("if buf is not None:");
        buffer.startBlock();
        buffer.addLine("buf.close()");
        buffer.endBlock();
        buffer.endBlock();
    }

    private void generateReturnResponseBody(Parameter parameter) {
        Type type = parameter.getType();
        buffer.addLine("buf = None");
        buffer.addLine("reader = None");
        buffer.addLine("try:");
        buffer.startBlock();
        buffer.addLine("buf = io.BytesIO(response.body)");
        buffer.addLine("reader = xml.XmlReader(buf)");
        if (type instanceof StructType) {
            PythonClassName reader = pythonNames.getReaderName(type);
            buffer.addLine("return readers.%1$s.read_one(reader)", reader.getClassName());
        }
        else if (type instanceof ListType) {
            ListType listType = (ListType) type;
            Type elementType = listType.getElementType();
            PythonClassName reader = pythonNames.getReaderName(elementType);
            buffer.addLine("return readers.%1$s.read_many(reader)", reader.getClassName());
        }
        buffer.endBlock();
        buffer.addLine("finally:");
        buffer.startBlock();
        buffer.addLine("if buf is not None:");
        buffer.startBlock();
        buffer.addLine("buf.close()");
        buffer.endBlock();
        buffer.addLine("if reader is not None:");
        buffer.startBlock();
        buffer.addLine("reader.close()");
        buffer.endBlock();
        buffer.endBlock();
    }

    private void generateHttpDelete(Method method) {
        // Get the parameters:
        List<Parameter> inParameters = method.parameters()
            .filter(Parameter::isIn)
            .collect(toList());

        // Begin method:
        Name name = method.getName();
        buffer.addLine("def %1$s(", pythonNames.getMemberStyleName(name));
        buffer.startBlock();
        buffer.addLine("self,");
        inParameters.forEach(this::generateFormalParameter);
        buffer.addLine("headers=None,");
        buffer.addLine("query=None,");
        buffer.endBlock();
        buffer.addLine("):");

        // Begin body:
        buffer.startBlock();
        generateActionDoc(method, Parameter::isIn);

        // Generate the code to check the types of the input parameters:
        buffer.addLine("# Check the types of the parameters:");
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        inParameters.forEach(this::generateCheckTypeTuple);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine();

        // Generate the code to build the URL query:
        buffer.addLine("# Build the URL:");
        buffer.addLine("query = query or {}");
        inParameters.forEach(this::generateUrlParameter);
        buffer.addLine();

        // Generate the code to build the headers:
        buffer.addLine("# Populate the headers:");
        buffer.addLine("headers = headers or {}");
        buffer.addLine();

        // Generate the method to send the request and wait for the response:
        buffer.addLine("# Send the request and wait for the response:");
        buffer.addLine("request = http.Request(method='DELETE', path=self._path, query=query, headers=headers)");
        buffer.addLine("response = self._connection.send(request)");
        buffer.addLine("if response.code not in [200]:");
        buffer.startBlock();
        buffer.addLine("self._check_fault(response)");
        buffer.endBlock();

        // End body:
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateFormalParameter(Parameter parameter) {
        buffer.addLine("%1$s=None,", pythonNames.getMemberStyleName(parameter.getName()));
    }

    private void generateUrlParameter(Parameter parameter) {
        Type type = parameter.getType();
        Name name = parameter.getName();
        String arg = pythonNames.getMemberStyleName(name);
        String tag = schemaNames.getSchemaTagName(name);
        buffer.addLine("if %1$s is not None:", arg);
        buffer.startBlock();
        if (type instanceof PrimitiveType) {
            Model model = type.getModel();
            if (type == model.getBooleanType()) {
                buffer.addLine("%1$s = Writer.render_boolean(%1$s)", arg);
            }
            else if (type == model.getIntegerType()) {
                buffer.addLine("%1$s = Writer.render_integer(%1$s)", arg);
            }
            else if (type == model.getDecimalType()) {
                buffer.addLine("%1$s = Writer.render_decimal(%1$s)", arg);
            }
            else if (type == model.getDateType()) {
                buffer.addLine("%1$s = Writer.render_date(%1$s)", arg);
            }
        }
        buffer.addLine("query['%1$s'] = %2$s", tag, arg);
        buffer.endBlock();
    }

    private void generateStr(Service service) {
        PythonClassName serviceName = pythonNames.getServiceName(service);
        buffer.addLine("def __str__(self):");
        buffer.startBlock();
        buffer.addLine("return '%1$s:%%s' %% self._path", serviceName.getClassName());
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateLocatorMember(Locator locator) {
        String memberName = pythonNames.getMemberStyleName(locator.getName());
        buffer.addLine("self._%1$s_service = None", memberName);
    }

    private void generateLocatorMethod(Locator locator) {
        Parameter parameter = locator.getParameters().stream().findFirst().orElse(null);
        if (parameter != null) {
            generateLocatorWithParameters(locator);
        }
        else {
            generateLocatorWithoutParameters(locator);
        }
    }

    private void generateLocatorWithParameters(Locator locator) {
        Parameter parameter = locator.parameters().findFirst().get();
        String methodName = pythonNames.getMemberStyleName(locator.getName());
        String argName = pythonNames.getMemberStyleName(parameter.getName());
        PythonClassName serviceName = pythonNames.getServiceName(locator.getService());
        buffer.addLine("def %1$s_service(self, %2$s):", methodName, argName);
        buffer.startBlock();
        generateDoc(locator);
        buffer.addLine("Service._check_types([");
        buffer.startBlock();
        generateCheckTypeTuple(parameter);
        buffer.endBlock();
        buffer.addLine("])");
        buffer.addLine("return %1$s(self._connection, '%%s/%%s' %% (self._path, %2$s))", serviceName.getClassName(),
            argName);
        buffer.endBlock();
        buffer.addLine();
    }

    private void generateLocatorWithoutParameters(Locator locator) {
        String methodName = pythonNames.getMemberStyleName(locator.getName());
        String urlSegment = getPath(locator.getName());
        PythonClassName serviceName = pythonNames.getServiceName(locator.getService());
        buffer.addLine("def %1$s_service(self):", methodName);
        buffer.startBlock();
        generateDoc(locator);
        buffer.addLine("return %1$s(self._connection, '%%s/%2$s' %% self._path)", serviceName.getClassName(),
            urlSegment);
        buffer.endBlock();
        buffer.addLine();
    }

    private void generatePathLocator(Service service) {
        // Begin method:
        buffer.addLine("def service(self, path):");
        buffer.startBlock();
        buffer.startComment();
        buffer.addLine("Service locator method, returns individual service on which the URI is dispatched.");
        buffer.endComment();

        buffer.addLine("if not path:");
        buffer.startBlock();
        buffer.addLine("return self");
        buffer.endBlock();

        // Generate the code that checks if the path corresponds to any of the locators without parameters:
        service.locators().filter(x -> x.getParameters().isEmpty()).sorted().forEach(locator -> {
            Name name = locator.getName();
            String segment = getPath(name);
            buffer.addLine("if path == '%1$s':", segment);
            buffer.startBlock();
            buffer.addLine(  "return self.%1$s_service()", pythonNames.getMemberStyleName(name));
            buffer.endBlock();
            buffer.addLine("if path.startswith('%1$s/'):", segment);
            buffer.startBlock();
            buffer.addLine(
                "return self.%1$s_service().service(path[%2$d:])",
                pythonNames.getMemberStyleName(name),
                segment.length() + 1
            );
            buffer.endBlock();
        });

        // If the path doesn't correspond to a locator without parameters, then it will correspond to the locator
        // with parameters, otherwise it is an error:
        Optional<Locator> optional = service.locators().filter(x -> !x.getParameters().isEmpty()).findAny();
        if (optional.isPresent()) {
            Locator locator = optional.get();
            Name name = locator.getName();
            buffer.addLine("index = path.find('/')");
            buffer.addLine("if index == -1:");
            buffer.startBlock();
            buffer.addLine("return self.%1$s_service(path)", pythonNames.getMemberStyleName(name));
            buffer.endBlock();
            buffer.addLine(
                "return self.%1$s_service(path[:index]).service(path[index + 1:])",
                pythonNames.getMemberStyleName(name)
            );
        }
        else {
            buffer.addLine("raise Error('The path \\\"%%s\\\" doesn\\'t correspond to any service' %% path)");
        }

        // End method:
        buffer.endBlock();
        buffer.addLine();
    }

    private String getPath(Name name) {
        return name.words().map(String::toLowerCase).collect(joining());
    }

    private void generateActionDoc(Method method, Predicate<Parameter> predicate) {
        buffer.startComment();
        if (method.getDoc() != null) {
            generateDocText(method);
            buffer.addLine();
        }
        if (method.parameters().filter(predicate).findFirst().orElse(null) != null) {
            List<String> lines = method.parameters()
                .filter(predicate)
                .filter(p -> p.getDoc() != null)
                .map(p -> String.format("`%s`:: %s", pythonNames.getMemberStyleName(p.getName()), p.getDoc()))
                .collect(toList());

            if (!lines.isEmpty()) {
                buffer.addLine("This method supports the following parameters:");
                buffer.addLine();
                lines.forEach(this::generateDocText);
                buffer.addLine("`headers`:: Additional HTTP headers.");
                buffer.addLine();
                buffer.addLine("`query`:: Additional URL query parameters.");
            }
        }
        buffer.endComment();
    }

    protected void generateDoc(Concept concept) {
        buffer.startComment();
        generateDocText(concept);
        buffer.endComment();
    }

    private void generateDocText(Concept concept) {
        generateDocText(concept.getDoc());
    }

    private void generateDocText(String doc) {
        List<String> lines = new ArrayList<>();
        if (doc != null) {
            Collections.addAll(lines, doc.split("\n"));
        }
        if (!lines.isEmpty()) {
            lines.stream().filter(l -> !l.isEmpty()).forEach(buffer::addRawLine);
            buffer.addLine();
        }
    }

    private Parameter getFirstParameter(Method method) {
        return method.parameters()
            .filter(x -> x.isIn() && x.isOut())
            .findFirst()
            .orElse(null);
    }

    private List<Parameter> getSecondaryParameters(Method method) {
        return method.parameters()
            .filter(x -> x.isIn() && !x.isOut())
            .sorted()
            .collect(toList());
    }

    private void generateSetActionAttribute(Parameter parameter) {
        String name = pythonNames.getMemberStyleName(parameter.getName());
        buffer.addLine("%1$s=%1$s,", name);
    }

    private void generateCheckTypeTuple(Parameter parameter) {
        String name = pythonNames.getMemberStyleName(parameter.getName());
        PythonTypeReference reference = pythonNames.getTypeReference(parameter.getType());
        buffer.addImports(reference.getImports());
        buffer.addLine("('%1$s', %1$s, %2$s),", name, reference.getText());
    }
}
