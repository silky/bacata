package entities.reply;

import entities.util.Content;

import java.util.List;
import java.util.Map;

/**
 * Created by Mauricio on 27/01/2017.
 */
public class ContentExecuteReplyOk extends Content {

    // -----------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------
    private String status;

    /**
     * This field represents the global kernel counter that increases by one with each request that stores history
     */
    private int executionCount;

    /**
     * This field represents a way to trigger frontend actions from the kernel.
     * (Payloads are considered deprecated, though their replacement is not yet implemented.)
     */
    private List<Map<String, String>> payload;

    /**
     * This field represents the results for the user_expressions
     */
    private Map<String, String> userExpressions;

    // -----------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------

    public ContentExecuteReplyOk(int executionCount, List<Map<String, String>> payload, Map<String, String> userExpressions) {
        this.status = "ok";
        this.executionCount = executionCount;
        this.payload = payload;
        this.userExpressions = userExpressions;
    }

    // -----------------------------------------------------------------
    // Methods
    // -----------------------------------------------------------------

    public String getStatus() {
        return status;
    }

    public int getExecutionCount() {
        return executionCount;
    }
}
