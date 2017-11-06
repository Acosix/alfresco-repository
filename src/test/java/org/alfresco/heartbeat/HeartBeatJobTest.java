/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.heartbeat;

import org.alfresco.heartbeat.datasender.HBData;
import org.alfresco.heartbeat.datasender.HBDataSenderService;
import org.alfresco.repo.lock.JobLockService;
import org.alfresco.repo.lock.LockAcquisitionException;
import org.alfresco.service.namespace.QName;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by mmuller on 27/10/2017.
 */
public class HeartBeatJobTest
{

    private HBDataSenderService mockDataSenderService;
    private JobLockService mockJobLockService;

    @Before
    public void setUp()
    {
        mockDataSenderService = mock(HBDataSenderService.class);
        mockJobLockService = mock(JobLockService.class);
    }

    private class SimpleHBDataCollector extends HBBaseDataCollector
    {

        public SimpleHBDataCollector(String collectorId)
        {
            super(collectorId);
        }

        public List<HBData> collectData()
        {
            List<HBData> result = new LinkedList<>();
            result.add(new HBData("systemId2", this.getCollectorId(), "1", new Date()));
            return result;
        }
    }

    @Test
    public void testJobInCluster() throws Exception
    {
        // mock the job context
        JobExecutionContext mockJobExecutionContext = mock(JobExecutionContext.class);
        JobDetail jobDetail = new JobDetail();
        when(mockJobExecutionContext.getJobDetail()).thenReturn(jobDetail);

        // create the hb collector
        SimpleHBDataCollector simpleCollector = spy(new SimpleHBDataCollector("simpleCollector"));
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("collector", simpleCollector);
        jobDataMap.put("hbDataSenderService", mockDataSenderService);
        jobDataMap.put("jobLockService", mockJobLockService);
        jobDetail.setJobDataMap(jobDataMap);

        // collector job is not locked from an other collector
        String lockToken = "locked";
        when(mockJobLockService.getLock(any(QName.class), anyLong())).thenReturn(lockToken);

        Runnable t1 = () ->
        {
            try
            {
                new HeartBeatJob().execute(mockJobExecutionContext);
            }
            catch (JobExecutionException e)
            {
                //
            }
        };
        Runnable t2 = () ->
        {
            try
            {
                new HeartBeatJob().execute(mockJobExecutionContext);
            }
            catch (JobExecutionException e)
            {
                //
            }
        };
        t1.run();
        // thread 1 keeps the lock
        when(mockJobLockService.getLock(isA(QName.class), anyLong())).thenThrow(new LockAcquisitionException("", ""));
        t2.run();

        // verify that we collected and send data but just one time
        verify(simpleCollector, Mockito.times(1)).collectData();
        verify(mockDataSenderService, Mockito.times(1)).sendData(any(List.class));
        verify(mockDataSenderService, Mockito.times(0)).sendData(any(HBData.class));
        verify(mockJobLockService, Mockito.times(2)).getLock(any(QName.class), anyLong());
        verify(mockJobLockService, Mockito.times(1)).refreshLock(eq(lockToken), any(QName.class), anyLong(), any(
                JobLockService.JobLockRefreshCallback.class));
        verify(mockJobLockService, Mockito.times(1)).releaseLock(eq(lockToken), any(QName.class));
    }
}
