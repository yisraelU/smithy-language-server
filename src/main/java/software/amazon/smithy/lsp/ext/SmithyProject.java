/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.SmithyInterface;
import software.amazon.smithy.lsp.Utils;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.FromSourceLocation;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.InputTrait;
import software.amazon.smithy.model.traits.OutputTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidatedResultException;

public final class SmithyProject {
    private final List<Path> imports;
    private final List<File> smithyFiles;
    private final List<File> externalJars;
    private Map<ShapeId, Location> locations = Collections.emptyMap();
    private final ValidatedResult<Model> model;
    private final File root;

    SmithyProject(List<Path> imports, List<File> smithyFiles, List<File> externalJars, File root,
            ValidatedResult<Model> model) {
        this.imports = imports;
        this.root = root;
        this.model = model;
        this.smithyFiles = smithyFiles;
        this.externalJars = externalJars;
        model.getResult().ifPresent(m -> this.locations = collectLocations(m));
    }

    /**
     * Recompile the model, adding a file to list of tracked files, potentially
     * excluding some other file.
     * <p>
     * This version of the method above is used when the
     * file is in ephemeral storage (temporary location when file is being changed)
     *
     * @param changed file which may or may not be already tracked by this project.
     * @param exclude file to exclude from being recompiled.
     * @return either an error, or a loaded project.
     */
    public Either<Exception, SmithyProject> recompile(File changed, File exclude) {
        List<File> newFiles = new ArrayList<>();

        for (File existing : onlyExistingFiles(this.smithyFiles)) {
            if (exclude != null && !existing.equals(exclude)) {
                newFiles.add(existing);
            }
        }

        if (changed.isFile()) {
            newFiles.add(changed);
        }

        return load(this.imports, newFiles, this.externalJars, this.root);
    }

    public ValidatedResult<Model> getModel() {
        return this.model;
    }

    public List<File> getExternalJars() {
        return this.externalJars;
    }

    public List<File> getSmithyFiles() {
        return this.smithyFiles;
    }

    public List<SmithyCompletionItem> getCompletions(String token, boolean isTrait, Optional<ShapeId> target) {
        return this.model.getResult().map(model -> Completions.find(model, token, isTrait, target))
                .orElse(Collections.emptyList());
    }

    public Map<ShapeId, Location> getLocations() {
        return this.locations;
    }

    /**
     * Load the project using a SmithyBuildExtensions configuration and workspace
     * root.
     *
     * @param config configuration.
     * @param root workspace root.
     * @return either an error or a loaded project.
     */
    public static Either<Exception, SmithyProject> load(SmithyBuildExtensions config, File root) {
        List<Path> imports = config.getImports().stream().map(p -> Paths.get(root.getAbsolutePath(), p).normalize())
                .collect(Collectors.toList());

        if (imports.isEmpty()) {
            imports.add(root.toPath());
        }

        LspLog.println("Imports from config: " + imports + " will be resolved against root " + root);

        List<File> smithyFiles = discoverSmithyFiles(imports, root);
        LspLog.println("Discovered smithy files: " + smithyFiles);

        List<File> externalJars = downloadExternalDependencies(config);
        LspLog.println("Downloaded external jars: " + externalJars);

        return load(imports, smithyFiles, externalJars, root);

    }

    /**
     * Run a selector expression against the loaded model in the workspace.
     * @param expression the selector expression.
     * @return list of locations of shapes that match expression.
     */
    public Either<Exception, List<Location>> runSelector(String expression) {
        try {
            Selector selector = Selector.parse(expression);
            Set<Shape> shapes = selector.select(this.model.unwrap());
            return Either.forRight(shapes.stream()
                    .map(shape -> this.locations.get(shape.getId()))
                    .collect(Collectors.toList()));
        } catch (ValidatedResultException e) {
            return Either.forLeft(e);
        }
    }

    private static Either<Exception, SmithyProject> load(List<Path> imports, List<File> smithyFiles,
            List<File> externalJars, File root) {
        Either<Exception, ValidatedResult<Model>> model = createModel(smithyFiles, externalJars);

        if (model.isLeft()) {
            return Either.forLeft(model.getLeft());
        } else {
            model.getRight().getValidationEvents().forEach(LspLog::println);
            return Either.forRight(new SmithyProject(imports, smithyFiles, externalJars, root, model.getRight()));
        }
    }

    private static Either<Exception, ValidatedResult<Model>> createModel(List<File> discoveredFiles,
            List<File> externalJars) {
        return SmithyInterface.readModel(discoveredFiles, externalJars);
    }

    public File getRoot() {
        return this.root;
    }

    private static Map<ShapeId, Location> collectLocations(Model model) {
        Map<ShapeId, Location> locations = new HashMap<>();
        List<String> modelFiles = model.shapes()
                .map(shape -> shape.getSourceLocation().getFilename())
                .distinct()
                .collect(Collectors.toList());
        for (String modelFile : modelFiles) {
            List<String> lines = getFileLines(modelFile);
            DocumentPreamble preamble = Document.detectPreamble(lines);
            Map<OperationShape, List<Shape>> operationsWithInlineInputOutputMap = new HashMap<>();
            Map<ShapeId, List<MemberShape>> containerMembersMap = new HashMap<>();
            int endMarker = getInitialEndMarker(lines);

             // Get shapes reverse-sorted by source location to work from bottom of file to top.
            List<Shape> shapes = model.shapes()
                    .filter(shape -> shape.getSourceLocation().getFilename().equals(modelFile))
                    // TODO: Once the change in https://github.com/awslabs/smithy/pull/1192 lands, replace with with
                    // `.sorted(Comparator.comparing(Shape::getSourceLocation).reversed())`.
                    .sorted(new SourceLocationSorter().reversed())
                    .collect(Collectors.toList());


            for (Shape shape : shapes) {
                SourceLocation sourceLocation = shape.getSourceLocation();
                Position startPosition = getStartPosition(sourceLocation);
                Position endPosition;
                if (endMarker < sourceLocation.getLine()) {
                    endPosition = new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
                } else {
                    endPosition = getEndPosition(endMarker, lines);
                }
                // If a shape belongs to an operation as an inlined input or output, collect a map of the operation
                // with the reversed ordered list of inputs and outputs within that operation. Once the location of
                // the containing operation has been determined, the map can be revisited to determine the locations of
                // the inlined inputs and outputs.
                Optional<OperationShape> matchingOperation = getOperationForInlinedInputOrOutput(model, shape,
                        preamble);
                if (matchingOperation.isPresent()) {
                    operationsWithInlineInputOutputMap.computeIfAbsent(matchingOperation.get(), s -> new ArrayList<>())
                            .add(shape);
                // Collect a map of container shapes and a list of member shapes, reverse ordered by source location
                // in the model file. This map will be revisited after the location of the containing shape has been
                // determined since it is needed to determine the locations of each member.
                } else if (shape.getType() == ShapeType.MEMBER) {
                    MemberShape memberShape = shape.asMemberShape().get();
                    ShapeId containerId = memberShape.getContainer();
                    containerMembersMap.computeIfAbsent(containerId, s -> new ArrayList<>()).add(memberShape);
                } else {
                    endMarker = advanceMarkerOnNonMemberShapes(startPosition, shape, lines, modelFile);
                    locations.put(shape.getId(), createLocation(modelFile, startPosition, endPosition));
                }
            }

            // Now that the location of each operation has been determined, the location of any inlined inputs and
            // outputs can be determined.
            for (Map.Entry<OperationShape, List<Shape>> entry : operationsWithInlineInputOutputMap.entrySet()) {
                OperationShape operation = entry.getKey();
                int operationEndMarker = locations.get(operation.getId()).getRange().getEnd().getLine();
                for (Shape shape : entry.getValue()) {
                    SourceLocation sourceLocation = shape.getSourceLocation();
                    Position startPosition = getStartPosition(sourceLocation);
                    Position endPosition = getEndPosition(operationEndMarker, lines);
                    Location location = createLocation(modelFile, startPosition, endPosition);
                    locations.put(shape.getId(), location);
                    operationEndMarker = sourceLocation.getLine() - 1;
                }
            }

            // Now that the locations of containing shape have been determined, including inlined input and output
            // structures, the location of any members can be determined.
            for (Map.Entry<ShapeId, List<MemberShape>> entry : containerMembersMap.entrySet()) {
                Location containerLocation = locations.get(entry.getKey());
                Range containerLocationRange = containerLocation.getRange();
                int memberEndMarker = containerLocationRange.getEnd().getLine();
                // Keep track of previous line to make sure that end marker has been advanced.
                String previousLine = "";
                // The member shapes were reverse ordered by source location when assembling this list, so we can
                // iterate through it as-is to work from bottom to top in the model file.
                for (MemberShape memberShape : entry.getValue()) {
                    int memberShapeSourceLocationLine = memberShape.getSourceLocation().getLine();
                    // If the member's source location matches the container location's starting line (with offset),
                    // the member is mixed in and not present in the model file.
                    if (memberShapeSourceLocationLine == containerLocationRange.getStart().getLine() + 1) {
                        locations.put(memberShape.getId(), createElidedMemberLocation(containerLocation));
                    // Otherwise, determine the correct location by trimming comments, empty lines and applied traits.
                    } else {
                        String currentLine = lines.get(memberEndMarker - 1).trim();
                        while (currentLine.startsWith("//") || currentLine.equals("") || currentLine.equals("}")
                                || currentLine.startsWith("@") || currentLine.equals(previousLine)) {
                            memberEndMarker = memberEndMarker - 1;
                            currentLine = lines.get(memberEndMarker - 1).trim();
                        }
                        Position startPosition = getStartPosition(memberShape.getSourceLocation());
                        Position endPosition = getEndPosition(memberEndMarker, lines);
                        // Advance the member end marker on any non-mixin traits on the current member, so that the next
                        // member location end is correct. Mixin traits will have been declared outside the
                        // containing shape and shouldn't impact determining the end location of the next member.
                        List<Trait> traits = memberShape.getAllTraits().values().stream()
                                .filter(trait -> !trait.getSourceLocation().equals(SourceLocation.NONE))
                                .filter(trait -> trait.getSourceLocation().getFilename().equals(modelFile))
                                .filter(trait -> !isFromMixin(containerLocationRange, trait))
                                .collect(Collectors.toList());
                        if (!traits.isEmpty()) {
                            traits.sort(new SourceLocationSorter());
                            memberEndMarker = traits.get(0).getSourceLocation().getLine();
                        }
                        locations.put(memberShape.getId(), createLocation(modelFile, startPosition, endPosition));
                        previousLine = currentLine;
                    }
                }
            }
        }
        return locations;
    }

    // Use an empty range at the container's start since elided members are not present in the model file.
    private static Location createElidedMemberLocation(Location containerLocation) {
        Position startPosition = containerLocation.getRange().getStart();
        Range memberRange = new Range(startPosition, startPosition);
        return new Location(containerLocation.getUri(), memberRange);
    }

    // If the trait was defined outside the container, it was mixed in.
    private static boolean isFromMixin(Range containerRange, Trait trait) {
        int traitLocationLine = trait.getSourceLocation().getLine();
        if (traitLocationLine < containerRange.getStart().getLine()
                || traitLocationLine > containerRange.getEnd().getLine()) {
            return true;
        }
        return false;
    }

    // Get the operation that matches an inlined input or output structure.
    private static Optional<OperationShape> getOperationForInlinedInputOrOutput(Model model, Shape shape,
                                                                                DocumentPreamble preamble) {
        if (preamble.getIdlVersion().isPresent()) {
            if (preamble.getIdlVersion().get().startsWith("2") && shape.isStructureShape()
                    && (shape.hasTrait(OutputTrait.class) || shape.hasTrait(InputTrait.class))) {
                String suffix = null;
                if (shape.hasTrait(InputTrait.class)) {
                    suffix = preamble.getOperationInputSuffix().orElse("Input");
                }

                if (shape.hasTrait(OutputTrait.class)) {
                    suffix = preamble.getOperationOutputSuffix().orElse("Output");
                }

                String shapeName = shape.getId().getName();
                String matchingOperationName = shapeName.substring(0, shapeName.length() - suffix.length());
                return model.shapes(OperationShape.class)
                        .filter(operationShape -> operationShape.getId().getName().equals(matchingOperationName))
                        .findFirst();
            }
        }
        return Optional.empty();
    }

    private static Location createLocation(String file, Position startPosition, Position endPosition) {
        return new Location(getUri(file), new Range(startPosition, endPosition));
    }

    private static int advanceMarkerOnNonMemberShapes(Position startPosition, Shape shape, List<String> fileLines,
                                                      String modelFile) {
        // When handling non-member shapes, advance the end marker for traits and comments above the current
        // shape.
        int marker = startPosition.getLine();
        // TODO: Handle traits being applied to shapes before shape definition in file.
        List<Trait> traits = shape.getAllTraits().values().stream()
                .filter(trait -> !trait.getSourceLocation().equals(SourceLocation.NONE))
                .filter(trait -> trait.getSourceLocation().getFilename().equals(modelFile))
                .collect(Collectors.toList());
        // If the shape has traits, advance the end marker again.
        if (!traits.isEmpty()) {
            // TODO: Replace with Comparator when this class is removed.
            traits.sort(new SourceLocationSorter());
            marker = traits.get(0).getSourceLocation().getLine() - 1;
        }
        // Move the end marker when encountering line comments or empty lines.
        if (fileLines.size() > marker) {
            while (fileLines.get(marker - 1).trim().startsWith("//")
                    || fileLines.get(marker - 1).trim().equals("")) {
                marker = marker - 1;
            }
        }
        return marker;
    }

    /**
     * Returns the shapeId of the shape that corresponds to the file uri and position within the model.
     *
     * @param uri String uri of model file.
     * @param position Cursor position within model file.
     * @return ShapeId of corresponding shape defined at location.
     */
    public Optional<ShapeId> getShapeIdFromLocation(String uri, Position position) {
        Comparator<Map.Entry<ShapeId, Location>> rangeSize = Comparator.comparing(entry ->
                entry.getValue().getRange().getEnd().getLine() - entry.getValue().getRange().getStart().getLine());
        return locations.entrySet().stream()
                .filter(entry -> entry.getValue().getUri().endsWith(Paths.get(uri).toString()))
                .filter(entry -> isPositionInRange(entry.getValue().getRange(), position))
                // Since the position is in each of the overlapping shapes, return the location with the smallest range.
                .sorted(rangeSize)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private boolean isPositionInRange(Range range, Position position) {
        if (range.getStart().getLine() > position.getLine()) {
            return false;
        }
        if (range.getEnd().getLine() < position.getLine()) {
            return false;
        }
        // For single-line ranges, make sure position is between start and end chars.
        if (range.getStart().getLine() == position.getLine()
                && range.getEnd().getLine() == position.getLine()) {
            return (range.getStart().getCharacter() <= position.getCharacter()
                    && range.getEnd().getCharacter() >= position.getCharacter());
        } else if (range.getStart().getLine() == position.getLine()) {
            return range.getStart().getCharacter() <= position.getCharacter();
        } else if (range.getEnd().getLine() == position.getLine()) {
            return range.getEnd().getCharacter() >= position.getCharacter();
        }
        return true;
    }

    private static int getInitialEndMarker(List<String> lines) {
        int endMarker = lines.size();
        // Remove empty lines from the end of the file.
        if (lines.size() > 0) {
            while (lines.get(endMarker - 1).trim().equals("")) {
                endMarker = endMarker - 1;
            }
        }
        return endMarker;
    }

    private static Position getStartPosition(SourceLocation sourceLocation) {
        return new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
    }

    // If the lines of the model were successfully loaded, return the end position of the actual shape line,
    // otherwise set it to the start of the next line.
    private static Position getEndPosition(int endMarker, List<String> lines) {
        if (lines.size() >= endMarker) {
            return new Position(endMarker - 1, lines.get(endMarker - 1).length());
        }
        return new Position(endMarker, 0);
    }

    private static List<String> getFileLines(String file) {
        try {
            if (Utils.isSmithyJarFile(file) || Utils.isJarFile(file)) {
                return Utils.jarFileContents(Utils.toSmithyJarFile(file));
            } else {
                return Files.readAllLines(Paths.get(file));
            }
        } catch (IOException e) {
            LspLog.println("File " + file + " could not be loaded.");
        }
        return Collections.emptyList();
    }

    private static String getUri(String fileName) {
        return Utils.isJarFile(fileName)
                ? Utils.toSmithyJarFile(fileName)
                : addFilePrefix(fileName);
    }

    private static String addFilePrefix(String fileName) {
        return !fileName.startsWith("file:") ? "file:" + fileName : fileName;
    }

    private static Boolean isValidSmithyFile(Path file) {
        String fName = file.getFileName().toString();
        return fName.endsWith(Constants.SMITHY_EXTENSION);
    }

    private static List<File> walkSmithyFolder(Path path, File root) {

        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).filter(SmithyProject::isValidSmithyFile).map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LspLog.println("Failed to walk import '" + path + "' from root " + root + ": " + e);
            return new ArrayList<>();
        }
    }

    private static List<File> discoverSmithyFiles(List<Path> imports, File root) {
        List<File> smithyFiles = new ArrayList<>();

        imports.forEach(path -> {
            if (Files.isDirectory(path)) {
                smithyFiles.addAll(walkSmithyFolder(path, root));
            } else if (isValidSmithyFile(path)) {
                smithyFiles.add(path.resolve(root.toPath()).toFile());
            }
        });
        return smithyFiles;
    }

    private static List<File> downloadExternalDependencies(SmithyBuildExtensions ext) {
        LspLog.println("Downloading external dependencies for " + ext);
        try {
            return DependencyDownloader.create(ext).download();
        } catch (Exception e) {
            LspLog.println("Failed to download external jars for " + ext + ": " + e);
            return Collections.emptyList();
        }
    }

    private static List<File> onlyExistingFiles(Collection<File> files) {
        return files.stream().filter(File::isFile).collect(Collectors.toList());
    }

    // TODO: Remove this Class once the change in https://github.com/awslabs/smithy/pull/1192 is available.
    private static class SourceLocationSorter implements Comparator<FromSourceLocation>, Serializable {
        @Override
        public int compare(FromSourceLocation s1, FromSourceLocation s2) {
            SourceLocation sourceLocation = s1.getSourceLocation();
            SourceLocation otherSourceLocation = s2.getSourceLocation();

            if (!sourceLocation.getFilename().equals(otherSourceLocation.getFilename())) {
                return sourceLocation.getFilename().compareTo(otherSourceLocation.getFilename());
            }

            int lineComparison = Integer.compare(sourceLocation.getLine(), otherSourceLocation.getLine());
            if (lineComparison != 0) {
                return lineComparison;
            }

            return Integer.compare(sourceLocation.getColumn(), otherSourceLocation.getColumn());
        }
    }
}
