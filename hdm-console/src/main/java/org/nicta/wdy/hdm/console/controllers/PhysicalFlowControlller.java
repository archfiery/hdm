package org.nicta.wdy.hdm.console.controllers;

import com.baidu.bpit.akka.server.SmsSystem;
import org.nicta.wdy.hdm.console.models.DagGraph;
import org.nicta.wdy.hdm.console.views.HDMViewAdapter;
import org.nicta.wdy.hdm.message.ExecutionTraceQuery;
import org.nicta.wdy.hdm.message.ExecutionTraceResp;
import org.nicta.wdy.hdm.message.LogicalFLowQuery;
import org.nicta.wdy.hdm.message.LogicalFLowResp;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Created by tiantian on 8/04/16.
 */
public class PhysicalFlowControlller extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String exeId = req.getParameter("executionTag");
        ExecutionTraceQuery msg = new ExecutionTraceQuery(exeId);
        String actor = AbstractController.master;
        ExecutionTraceResp res = (ExecutionTraceResp) SmsSystem.askSync(actor, msg).get();
        DagGraph vo = HDMViewAdapter.executionTraceToGraph(res);
        String json = ObjectUtils.objectToJson(vo);
        resp.setContentType("application/json");
        resp.getWriter().write(json);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }
}