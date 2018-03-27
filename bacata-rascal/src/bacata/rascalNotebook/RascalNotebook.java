package bacata.rascalNotebook;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.load.StandardLibraryContributor;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.repl.CompletionResult;
import org.rascalmpl.repl.ILanguageProtocol;
import org.rascalmpl.repl.RascalInterpreterREPL;
import org.rascalmpl.shell.ShellEvaluatorFactory;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;
import org.zeromq.ZMQ.Socket;
import communication.Header;
import entities.ContentExecuteInput;
import entities.ContentStream;
import entities.reply.ContentCompleteReply;
import entities.reply.ContentExecuteReplyOk;
import entities.reply.ContentExecuteResult;
import entities.reply.ContentIsCompleteReply;
import entities.reply.ContentKernelInfoReply;
import entities.reply.ContentShutdownReply;
import entities.request.ContentCompleteRequest;
import entities.request.ContentExecuteRequest;
import entities.request.ContentIsCompleteRequest;
import entities.request.ContentShutdownRequest;
import entities.util.MessageType;
import entities.util.Status;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IValueFactory;
import server.JupyterServer;

public class RascalNotebook extends JupyterServer{
	
		// -----------------------------------------------------------------
		// Fields
		// -----------------------------------------------------------------

		private int executionNumber;

		private ILanguageProtocol language;

		private StringWriter stdout;

		private StringWriter stderr;

		// -----------------------------------------------------------------
		// Constructor
		// -----------------------------------------------------------------

		public RascalNotebook(String connectionFilePath, String... salixPath ) throws Exception {
			super(connectionFilePath);
			executionNumber = 1;
			stdout = new StringWriter();
			stderr = new StringWriter();
			this.language = makeInterpreter(null, null, null, salixPath);
			this.language.initialize(stdout, stderr);
			startServer();
		}

		// -----------------------------------------------------------------
		// Methods
		// -----------------------------------------------------------------

		@Override
		public void processExecuteRequest(Header parentHeader, ContentExecuteRequest contentExecuteRequest, Map<String, String> metadata) {
			if(!contentExecuteRequest.isSilent())
			{
				if(contentExecuteRequest.isStoreHistory())
				{
					sendMessage(getCommunication().getPublish(),createHeader(parentHeader.getSession(), MessageType.EXECUTE_INPUT), parentHeader, metadata, new ContentExecuteInput(contentExecuteRequest.getCode(), executionNumber));

					try {
						Map<String, String> data = new HashMap<>();
						
						// TODO: Should I add some value to the metadata which describes wheter the result is a visualization or not?
						this.language.handleInput(contentExecuteRequest.getCode(), data, metadata);
						sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_REPLY), parentHeader, metadata, new ContentExecuteReplyOk(executionNumber));

						if(!stdout.toString().trim().equals("")){
							sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.STREAM), parentHeader, metadata, new ContentStream("stdout", stdout.toString()));
							stdout.getBuffer().setLength(0);
							stdout.flush();
						}

						if(!stderr.toString().trim().equals("")){
							sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.STREAM), parentHeader, metadata, new ContentStream("stderr", stderr.toString()));
							stderr.getBuffer().setLength(0);
							stderr.flush();
						}

						// sends the result
						if(!data.isEmpty())
						{
							ContentExecuteResult content = new ContentExecuteResult(executionNumber, data, metadata);
							sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_RESULT), parentHeader, metadata, content);
						}

					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				else{
					// TODO evaluate user code 
				}
				executionNumber ++;
			}
			else{
				// No broadcast output on the IOPUB channel.
				// Don't have an execute_result.
				sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_REPLY), parentHeader, metadata, new ContentExecuteReplyOk(executionNumber));
			}
		}

		@Override
		public void processHistoryRequest(Header parentHeader, Map<String, String> metadata) {
			// TODO This is only for clients to explicitly request history from a kernel
		}
		@Override
		public void processKernelInfoRequest(Header parentHeader, Map<String, String> metadata){
			sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.KERNEL_INFO_REPLY), parentHeader, metadata, new ContentKernelInfoReply());
		}

		@Override
		public void processShutdownRequest(Socket socket, Header parentHeader, ContentShutdownRequest contentShutdown, Map<String, String> metadata) {
			boolean restart = false;
			if(contentShutdown.getRestart())
			{
				restart = true;
				// TODO: how can I restart rascal?
			}
			else{
				this.language.stop();
				getCommunication().getRequests().close();
				getCommunication().getPublish().close();
				getCommunication().getControl().close();
				getCommunication().getContext().close();
				getCommunication().getContext().term();
				System.exit(-1);
			}
			sendMessage(socket, createHeader(parentHeader.getSession(), MessageType.SHUTDOWN_REPLY), parentHeader, metadata, new ContentShutdownReply(restart));
		}

		/**
		 * This method is executed when the kernel receives a is_complete_request message.
		 */
		@Override
		public void processIsCompleteRequest(Header header, ContentIsCompleteRequest request, Map<String, String> metadata) {
			//TODO: Rascal supports different statuses? (e.g. complete, incomplete, invalid or unknown?
			String status, indent="";
			if(this.language.isStatementComplete(request.getCode())){
				status = Status.COMPLETE;
			}
			else{
				status = Status.INCOMPLETE;
				indent = "??????";
			}
			sendMessage(getCommunication().getRequests(), createHeader(header.getSession(), MessageType.IS_COMPLETE_REPLY), header, metadata, new ContentIsCompleteReply(status, indent));
		}

		@Override
		public void processCompleteRequest(Header parentHeader, ContentCompleteRequest request, Map<String, String> metadata) {
			int cursorStart =0;
			ArrayList<String> sugestions;
			if(request.getCode().startsWith("import ")){
				cursorStart=7;
			}
			CompletionResult result =this.language.completeFragment(request.getCode(), request.getCursorPosition());
			if(result != null)
				sugestions = (ArrayList<String>)result.getSuggestions();
			else 
				sugestions = null;
			ContentCompleteReply content = new ContentCompleteReply(sugestions, cursorStart, request.getCode().length(), new HashMap<String, String>(), Status.OK);
			sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.COMPLETE_REPLY), parentHeader, metadata, content);
		}
		
		private static ISourceLocation createJarLocation(IValueFactory vf, URL u) throws URISyntaxException {
			String full = u.toString();
			if (full.startsWith(JAR_FILE_PREFIX)) {
				full = full.substring(JAR_FILE_PREFIX.length());
				return vf.sourceLocation("jar", null, full);
			}
			else {
				return vf.sourceLocation(URIUtil.fromURL(u));
			}
		}
		
		private static final String JAR_FILE_PREFIX = "jar:file:";

		@Override
		public ILanguageProtocol makeInterpreter(String source, String moduleName, String variableName, final String... salixPath) throws IOException, URISyntaxException {
			return new RascalInterpreterREPL(true) {
				@Override
				protected Evaluator constructEvaluator(Writer stdout, Writer stderr) {
					Evaluator e = ShellEvaluatorFactory.getDefaultEvaluator(new PrintWriter(stdout), new PrintWriter(stderr));
					try {
						e.addRascalSearchPathContributor(StandardLibraryContributor.getInstance());
						
						//
						IValueFactory vf = ValueFactoryFactory.getValueFactory();
						Enumeration<URL> res = ClassLoader.getSystemClassLoader().getResources(RascalManifest.META_INF_RASCAL_MF);
						RascalManifest mf = new RascalManifest();
						while (res.hasMoreElements()) {
							URL next = res.nextElement();
							List<String> roots = mf.getManifestSourceRoots(next.openStream());
							if (roots != null) {
								ISourceLocation currentRoot = createJarLocation(vf, next);
								currentRoot = URIUtil.getParentLocation(URIUtil.getParentLocation(currentRoot));
								for (String r: roots) {
									e.addRascalSearchPath(URIUtil.getChildLocation(currentRoot, r));
								}
								e.addRascalSearchPath(URIUtil.getChildLocation(currentRoot, RascalManifest.DEFAULT_SRC));
							}
						}
						//
						e.addRascalSearchPath(URIUtil.createFromURI((salixPath[0])));
					} catch (URISyntaxException | IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					return e;
				}
			};
		}
		
		// -----------------------------------------------------------------
		// Execution
		// -----------------------------------------------------------------

		public static void main(String[] args) {
			try {
				new RascalNotebook(args[0], args[1]);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

}