package com.moelholm.tools.mediaorganizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Component;

@Component
public class MediaOrganizer {

    // --------------------------------------------------------------------------------------------------------------------------------------------
    // Constants
    // --------------------------------------------------------------------------------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(MediaOrganizer.class);

    // --------------------------------------------------------------------------------------------------------------------------------------------
    // Member fields
    // --------------------------------------------------------------------------------------------------------------------------------------------

    @Autowired
    private MediaOrganizerConfiguration configuration;

    @Autowired
    private TaskScheduler scheduler;

    // --------------------------------------------------------------------------------------------------------------------------------------------
    // Public API
    // --------------------------------------------------------------------------------------------------------------------------------------------

    public void scheduleJobThatUndoesFlatMess(Path from, Path to) {

        if (hasInvalidParameters(from, to)) {
            return;
        }

        CronTrigger trigger = new CronTrigger(configuration.getScheduleAsCronExpression());
        scheduler.schedule(() -> undoFlatMess(from, to), trigger);

        LOG.info("Scheduled job that will move files from [{}] to [{}]", from, to);
        Date nextExecutionTime = trigger.nextExecutionTime(new SimpleTriggerContext());
        LOG.info("    - Job will start at [{}]", formatDateAsString(nextExecutionTime));
    }

    @Async
    public void undoFlatMessAsync(Path from, Path to) {
        undoFlatMess(from, to);
    }

    public void undoFlatMess(Path from, Path to) {

        if (hasInvalidParameters(from, to)) {
            return;
        }

        LOG.info("Moving files from [{}] to [{}]", from, to);
        allFilesFromPath(from) //
                .filter(selectMediaFiles())//
                .collect(groupByYearMonthDayString()) //
                .forEach((folderName, mediaFilePaths) -> {
                    LOG.info("Processing folder [{}] which has [{}] media files", folderName, mediaFilePaths.size());
                    Path destinationFolderPath = to.resolve(generateRealFolderName(folderName, mediaFilePaths));
                    mediaFilePaths.stream().forEach(p -> {
                        Path destinationFilePath = destinationFolderPath.resolve(p.getFileName());
                        LOG.info("    {}", destinationFilePath.getFileName());
                        if (destinationFilePath.toFile().exists()) {
                            LOG.info("File [{}] exists at destination folder - so skipping that", destinationFilePath.getFileName());
                        } else {
                            moveFile(p, destinationFolderPath.resolve(p.getFileName()));
                        }
                    });
                });
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------
    // Private functionality
    // --------------------------------------------------------------------------------------------------------------------------------------------

    private Collector<Path, ?, Map<String, List<Path>>> groupByYearMonthDayString() {
        return Collectors.groupingBy(p -> toYearMonthDayString(p));
    }

    private Stream<Path> allFilesFromPath(Path from) {
        try {
            return Files.list(from);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Predicate<? super Path> selectMediaFiles() {
        return p -> {
            // TODO functionalize ;)
            for (String mediaFileExtensionsToMatch : configuration.getMediaFileExtensionsToMatch()) {
                if (p.toString().toLowerCase().endsWith(String.format(".%s", mediaFileExtensionsToMatch))) {
                    return true;
                }
            }
            return false;
        };
    }

    private boolean hasInvalidParameters(Path from, Path to) {

        boolean result = false;

        if (isDirectoryThatDoesNotExist(from)) {
            LOG.info("Argument [from] is not an existing directory");
            result = true;
        }

        if (isDirectoryThatDoesNotExist(to)) {
            LOG.info("Argument [to] is not an existing directory");
            result = true;
        }

        return result;
    }

    private boolean isDirectoryThatDoesNotExist(Path pathToTest) {
        return (pathToTest == null) || !(pathToTest.toFile().isDirectory());
    }

    private String toYearMonthDayString(Path path) {
        Date date = parseDateFromPathName(path);

        if (date == null) {
            return "unknown";
        }

        Calendar dateCal = Calendar.getInstance();
        dateCal.setTime(date);

        int year = dateCal.get(Calendar.YEAR);
        String month = new DateFormatSymbols(configuration.getLocale()).getMonths()[dateCal.get(Calendar.MONTH)];
        month = Character.toUpperCase(month.charAt(0)) + month.substring(1);
        int day = dateCal.get(Calendar.DAY_OF_MONTH);

        return String.format("%s - %s - %s", year, month, day);
    }

    private String generateRealFolderName(String folderName, List<Path> mediaFilePaths) {
        String lastPartOfFolderName = "( - \\d+)$";
        String replaceWithNewLastPartOfFolderName;
        if (mediaFilePaths.size() >= configuration.getAmountOfMediaFilesIndicatingAnEvent()) {
            replaceWithNewLastPartOfFolderName = String.format("$1 - %s", configuration.getSuffixForDestinationFolderOfUnknownEventMediaFiles());
        } else {
            replaceWithNewLastPartOfFolderName = String.format(" - %s", configuration.getSuffixForDestinationFolderOfMiscMediaFiles());
        }
        return folderName.replaceAll(lastPartOfFolderName, replaceWithNewLastPartOfFolderName);
    }

    private Date parseDateFromPathName(Path path) {
        SimpleDateFormat sdf = new SimpleDateFormat(configuration.getMediaFilesDatePattern());
        try {
            return sdf.parse(path.getFileName().toString());
        } catch (ParseException e) {
            LOG.warn("Failed to extract date from {} (Cause says: {})", path, e.getMessage());
            return null;
        }
    }

    private String formatDateAsString(Date date) {
        return DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL).format(date);
    }

    private void moveFile(Path fromFilePath, Path toFilePath) {
        try {
            ensureDirectoryStructureExists(toFilePath.getParent());
            Files.move(fromFilePath, toFilePath);
        } catch (IOException e) {
            LOG.warn(String.format("Failed to copy file from [%s] to [%s]", fromFilePath, toFilePath), e);
        }
    }

    private void ensureDirectoryStructureExists(Path directoryPath) {
        if (directoryPath != null && !directoryPath.toFile().exists()) {
            directoryPath.toFile().mkdirs();
        }
    }
}
