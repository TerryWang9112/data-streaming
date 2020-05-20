package io.metersphere.streaming.service;

import io.metersphere.streaming.base.domain.LoadTestReport;
import io.metersphere.streaming.base.domain.LoadTestReportDetail;
import io.metersphere.streaming.base.domain.LoadTestReportDetailExample;
import io.metersphere.streaming.base.domain.LoadTestWithBLOBs;
import io.metersphere.streaming.base.mapper.LoadTestMapper;
import io.metersphere.streaming.base.mapper.LoadTestReportDetailMapper;
import io.metersphere.streaming.base.mapper.LoadTestReportMapper;
import io.metersphere.streaming.base.mapper.ext.ExtLoadTestMapper;
import io.metersphere.streaming.base.mapper.ext.ExtLoadTestReportMapper;
import io.metersphere.streaming.commons.constants.TestStatus;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.model.Metric;
import io.metersphere.streaming.report.ReportGeneratorFactory;
import io.metersphere.streaming.report.impl.AbstractReport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TestResultService {
    @Resource
    private LoadTestReportMapper loadTestReportMapper;
    @Resource
    private ExtLoadTestReportMapper extLoadTestReportMapper;
    @Resource
    private LoadTestMapper loadTestMapper;
    @Resource
    private LoadTestReportDetailMapper loadTestReportDetailMapper;
    @Resource
    private ExtLoadTestMapper extLoadTestMapper;

    ExecutorService completeThreadPool = Executors.newFixedThreadPool(10);
    ExecutorService reportThreadPool = Executors.newFixedThreadPool(30);

    public void savePartContent(String reportId, String testId, String content) {
        // 更新状态
        extLoadTestReportMapper.updateStatus(reportId, TestStatus.Running.name(), TestStatus.Starting.name());
        extLoadTestMapper.updateStatus(testId, TestStatus.Running.name(), TestStatus.Starting.name());

        LoadTestReportDetailExample example = new LoadTestReportDetailExample();
        example.createCriteria().andReportIdEqualTo(reportId);
        long part = loadTestReportDetailMapper.countByExample(example);
        LoadTestReportDetail record = new LoadTestReportDetail();
        record.setReportId(reportId);
        record.setPart(part + 1);
        record.setContent(content);
        loadTestReportDetailMapper.insert(record);
    }

    public String convertToLine(Metric metric) {
        //timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
        long start = metric.getTimestamp().getTime();
        Date end = metric.getElapsedTime();
        StringBuilder content = new StringBuilder();
        content.append(start).append(",");
        int elapsed = getElapsed(end);
        content.append(elapsed).append(",");
        content.append(metric.getSampleLabel()).append(",");
        content.append(metric.getResponseCode()).append(",");
        // response message
        content.append(",");
        content.append(metric.getThreadName()).append(",");
        content.append(metric.getDataType()).append(",");
        content.append(metric.getSuccess()).append(",");
        // failure message contains \n
        String message = convertFailureMessage(metric);
        content.append(message).append(",");
        content.append(metric.getBytes()).append(",");
        content.append(metric.getSentBytes()).append(",");
        content.append(metric.getGrpThreads()).append(",");
        content.append(metric.getAllThreads()).append(",");
        // 处理url换行问题
        if (StringUtils.isNotBlank(metric.getUrl())) {
            content.append(StringUtils.deleteWhitespace(metric.getUrl())).append(",");
        } else {
            content.append(",");
        }
        content.append(metric.getLatency()).append(",");
        content.append(metric.getIdleTime()).append(",");
        content.append(metric.getConnectTime()).append("\n");
        return content.toString();
    }

    private String convertFailureMessage(Metric metric) {
        String message = StringUtils.remove(metric.getFailureMessage(), "\n");
        message = StringUtils.replace(message, ",", " ");
        return message;
    }

    private int getElapsed(Date end) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(end);
        int hours = calendar.get(Calendar.HOUR_OF_DAY);
        int minutes = calendar.get(Calendar.MINUTE);
        int seconds = calendar.get(Calendar.SECOND);
        return minutes * 60 + seconds;
    }

    public void completeReport(Metric metric) {
        LoadTestReport report = loadTestReportMapper.selectByPrimaryKey(metric.getReportId());
        LogUtil.info("test tearDown message received, report:{}, test:{} ", report.getId(), report.getTestId());
        report.setUpdateTime(System.currentTimeMillis());
        report.setStatus(TestStatus.Reporting.name());
        loadTestReportMapper.updateByPrimaryKeySelective(report);
        // 更新测试的状态
        LoadTestWithBLOBs loadTest = new LoadTestWithBLOBs();
        loadTest.setId(metric.getTestId());
        loadTest.setStatus(TestStatus.Reporting.name());
        loadTestMapper.updateByPrimaryKeySelective(loadTest);
        LogUtil.info("test reporting: " + metric.getTestName());

        // TODO 结束测试，生成报告
        completeThreadPool.execute(() -> {
            generateReport(metric.getReportId());
        });
    }

    public void generateReport(String reportId) {
        LoadTestReportDetailExample example = new LoadTestReportDetailExample();
        example.createCriteria().andReportIdEqualTo(reportId);
        example.setOrderByClause("part");
        List<LoadTestReportDetail> loadTestReportDetails = loadTestReportDetailMapper.selectByExampleWithBLOBs(example);
        List<AbstractReport> reportGenerators = ReportGeneratorFactory.getReportGenerators();
        LogUtil.info("report generators size: {}", reportGenerators.size());
        CountDownLatch countDownLatch = new CountDownLatch(reportGenerators.size());
        reportGenerators.forEach(r -> reportThreadPool.execute(() -> {
            String content = loadTestReportDetails.stream().map(LoadTestReportDetail::getContent).reduce("", (a, b) -> a + b);
            r.init(reportId, content);
            try {
                r.execute();
            } finally {
                countDownLatch.countDown();
            }
        }));
        try {
            countDownLatch.await();
            LoadTestReport report = loadTestReportMapper.selectByPrimaryKey(reportId);
            report.setUpdateTime(System.currentTimeMillis());
            report.setStatus(TestStatus.Completed.name());
            loadTestReportMapper.updateByPrimaryKeySelective(report);
            // 更新测试的状态
            LoadTestWithBLOBs loadTest = new LoadTestWithBLOBs();
            loadTest.setId(report.getTestId());
            loadTest.setStatus(TestStatus.Completed.name());
            loadTestMapper.updateByPrimaryKeySelective(loadTest);
            LogUtil.info("test completed: " + report.getTestId());
        } catch (InterruptedException e) {
            LogUtil.error(e);
        }
    }

}
