package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hubspot.smtp.messages.MessageContent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class SmtpSessionTest {
  private static final SmtpRequest SMTP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final MessageContent SMTP_CONTENT = MessageContent.of(Unpooled.copiedBuffer(new byte[1]));
  private static final SmtpResponse OK_RESPONSE = new DefaultSmtpResponse(250, "OK");
  private static final SmtpResponse FAIL_RESPONSE = new DefaultSmtpResponse(400, "nope");
  private static final SmtpResponse INTERMEDIATE_RESPONSE = new DefaultSmtpResponse(300, "... go on");
  private static final SmtpRequest MAIL_REQUEST = new DefaultSmtpRequest(SmtpCommand.MAIL, "FROM:alice@example.com");
  private static final SmtpRequest RCPT_REQUEST = new DefaultSmtpRequest(SmtpCommand.RCPT, "FROM:bob@example.com");
  private static final SmtpRequest DATA_REQUEST = new DefaultSmtpRequest(SmtpCommand.DATA);
  private static final SmtpRequest EHLO_REQUEST = new DefaultSmtpRequest(SmtpCommand.EHLO);
  private static final SmtpRequest NOOP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpRequest HELO_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELO);
  private static final SmtpRequest HELP_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELP);

  private static final ExecutorService EXECUTOR_SERVICE = MoreExecutors.newDirectExecutorService();
  private static final SmtpSessionConfig CONFIG = SmtpSessionConfig.forRemoteAddress("127.0.0.1", 25);

  private ResponseHandler responseHandler;
  private CompletableFuture<SmtpResponse[]> responseFuture;
  private Channel channel;
  private ChannelPipeline pipeline;
  private SmtpSession session;

  @Before
  public void setup() {
    channel = mock(Channel.class);
    pipeline = mock(ChannelPipeline.class);
    responseHandler = mock(ResponseHandler.class);

    responseFuture = new CompletableFuture<>();
    when(responseHandler.createResponseFuture(anyInt(), any())).thenReturn(responseFuture);
    when(channel.pipeline()).thenReturn(pipeline);
    when(channel.alloc()).thenReturn(new PooledByteBufAllocator(false));

    session = new SmtpSession(channel, responseHandler, EXECUTOR_SERVICE, CONFIG);
    session.parseEhloResponse(Lists.newArrayList("PIPELINING", "AUTH PLAIN LOGIN"));
  }
  
  @Test
  public void itSendsRequests() {
    session.send(SMTP_REQUEST);

    verify(channel).writeAndFlush(SMTP_REQUEST);
  }

  @Test
  public void itSendsContents() {
    session.send(SMTP_CONTENT);

    verify(channel).write(SMTP_CONTENT.get7BitEncodedContent());
    verify(channel).write(EMPTY_LAST_CONTENT);
    verify(channel).flush();
  }

  @Test
  public void itWrapsTheResponse() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);

    responseFuture.complete(new SmtpResponse[] {OK_RESPONSE});

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().code()).isEqualTo(OK_RESPONSE.code());
    assertThat(future.get().details()).isEqualTo(OK_RESPONSE.details());
  }

  @Test
  public void itParsesTheEhloResponse() {
    session.send(EHLO_REQUEST);

    responseFuture.complete(new SmtpResponse[] { new DefaultSmtpResponse(250,
        "smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "8BITMIME",
        "STARTTLS",
        "SIZE") });

    assertThat(session.getEhloResponse().isSupported(Extension.EIGHT_BIT_MIME)).isTrue();
    assertThat(session.getEhloResponse().isSupported(Extension.STARTTLS)).isTrue();
    assertThat(session.getEhloResponse().isSupported(Extension.SIZE)).isTrue();

    assertThat(session.getEhloResponse().isSupported(Extension.PIPELINING)).isFalse();

    assertThat(session.getEhloResponse().isAuthPlainSupported()).isTrue();
    assertThat(session.getEhloResponse().isAuthLoginSupported()).isTrue();
  }

  @Test
  public void itParsesAnEmptyEhloResponse() {
    session.send(EHLO_REQUEST);

    responseFuture.complete(new SmtpResponse[] { new DefaultSmtpResponse(250,
        "smtp.example.com Hello client.example.com") });

    assertThat(session.getEhloResponse().isSupported(Extension.EIGHT_BIT_MIME)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.STARTTLS)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.SIZE)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.PIPELINING)).isFalse();

    assertThat(session.getEhloResponse().isAuthPlainSupported()).isFalse();
    assertThat(session.getEhloResponse().isAuthLoginSupported()).isFalse();
  }

  @Test
  public void itExecutesReturnedFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, executorService, CONFIG);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Void> assertionFuture = future.thenRun(() -> assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor"));

    responseFuture.complete(new SmtpResponse[] {OK_RESPONSE});
    assertionFuture.join();
  }

  @Test
  public void itExecutesReturnedExceptionalFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, executorService, CONFIG);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Boolean> assertionFuture = future.handle((r, e) -> {
      assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor");
      return true;
    });

    responseFuture.completeExceptionally(new RuntimeException());
    assertionFuture.join();
  }

  @Test
  public void itThrowsIllegalStateIfPipeliningIsNotSupported() {
    session.parseEhloResponse(Collections.emptyList());

    assertThatThrownBy(() -> session.sendPipelined(SMTP_CONTENT, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Pipelining is not supported on this server");
  }

  @Test
  public void itSendsPipelinedRequests() {
    session.sendPipelined(SMTP_CONTENT, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(SMTP_CONTENT.get7BitEncodedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).write(RCPT_REQUEST);
    order.verify(channel).write(DATA_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itCanSendASingleCommandWithPipelined() {
    // this is the same as just calling send
    session.sendPipelined(MAIL_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itChecksPipelineArgumentsAreValid() {
    assertPipelineError("DATA must appear last in a pipelined request", DATA_REQUEST, MAIL_REQUEST);
    assertPipelineError("EHLO must appear last in a pipelined request", EHLO_REQUEST, MAIL_REQUEST);
    assertPipelineError("NOOP must appear last in a pipelined request", NOOP_REQUEST, MAIL_REQUEST);

    assertPipelineError("HELO cannot be used in a pipelined request", HELO_REQUEST);
    assertPipelineError("HELP cannot be used in a pipelined request", HELP_REQUEST);
  }

  private void assertPipelineError(String message, SmtpRequest... requests) {
    assertThatThrownBy(() -> session.sendPipelined(requests))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  @Test
  public void itWrapsTheResponsesWhenPipelining() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse[]> future = session.sendPipelined(SMTP_CONTENT, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    SmtpResponse[] responses = {OK_RESPONSE, OK_RESPONSE, OK_RESPONSE, OK_RESPONSE};
    responseFuture.complete(responses);

    // 4 responses expected: one for the content, 3 for the requests
    verify(responseHandler).createResponseFuture(eq(4), any());

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().length).isEqualTo(responses.length);
    assertThat(future.get()[0].getSession()).isEqualTo(session);
    assertThat(future.get()[0].code()).isEqualTo(OK_RESPONSE.code());
  }

  @Test
  public void itExpectsTheRightNumberOfResponsesWhenPipelining() {
    session.sendPipelined(RCPT_REQUEST, DATA_REQUEST);

    // 1 response expected for each request
    verify(responseHandler).createResponseFuture(eq(2), any());
  }

  @Test
  public void itWillNotAuthenticateWithAuthPlainUnlessTheServerSupportsIt() {
    session.parseEhloResponse(Collections.emptyList());

    assertThatThrownBy(() -> session.authPlain("user", "password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Auth plain is not supported on this server");
  }

  @Test
  public void itCanAuthenticateWithAuthPlain() {
    String username = "user";
    String password = "password";
    String encoded = Base64.getEncoder().encodeToString(String.format("%s\0%s\0%s", username, username, password).getBytes());

    session.authPlain(username, password);

    verify(channel).writeAndFlush(new DefaultSmtpRequest("AUTH", "PLAIN", encoded));
  }

  @Test
  public void itWillNotAuthenticateWithAuthLoginUnlessTheServerSupportsIt() {
    session.parseEhloResponse(Collections.emptyList());

    assertThatThrownBy(() -> session.authLogin("user", "password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Auth login is not supported on this server");
  }

  @Test
  public void itCanAuthenticateWithAuthLogin() throws Exception {
    String username = "user";
    String password = "password";

    // do the initial request, which just includes the username
    session.authLogin("user", "password");

    verify(channel).writeAndFlush(new DefaultSmtpRequest("AUTH", "LOGIN", encodeBase64(username)));

    // now the second request, which sends the password
    responseFuture.complete(new SmtpResponse[] {INTERMEDIATE_RESPONSE});

    // this is sent to the second invocation of writeAndFlush
    ArgumentCaptor<Object> bufCaptor = ArgumentCaptor.forClass(Object.class);
    verify(channel, times(2)).writeAndFlush(bufCaptor.capture());
    ByteBuf capturedBuffer = (ByteBuf) bufCaptor.getAllValues().get(1);

    String actualString = capturedBuffer.toString(0, capturedBuffer.readableBytes(), StandardCharsets.UTF_8);
    assertThat(actualString).isEqualTo(encodeBase64(password) + "\r\n");
  }

  @Test
  public void itReturnsTheResponseIfAuthLoginFailsAtTheFirstRequest() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.authLogin("user", "password");

    responseFuture.complete(new SmtpResponse[] {FAIL_RESPONSE});

    assertThat(f.get().code()).isEqualTo(FAIL_RESPONSE.code());
  }

  @Test
  public void itRedactsAuthCommandsInTheDebugString() {
    assertThat(SmtpSession.createDebugString(new DefaultSmtpRequest("AUTH", "super-secret")))
        .isEqualTo("<redacted-auth-command>");
  }

  @Test
  public void itIncludesCommandsAndArgsInTheDebugString() {
    assertThat(SmtpSession.createDebugString(new DefaultSmtpRequest("EHLO", "example.com"), new DefaultSmtpRequest("AUTH", "super-secret")))
        .isEqualTo("EHLO example.com, <redacted-auth-command>");

  }

  private String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void itClosesTheUnderlyingChannel() {
    DefaultChannelPromise channelPromise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    when(channel.close()).thenReturn(channelPromise);

    CompletableFuture<Void> f = session.close();
    channelPromise.setSuccess();

    assertThat(f.isDone());
  }

  @Test
  public void itCompletesCloseFutureWhenTheConnectionIsClosed() throws Exception {
    assertThat(session.getCloseFuture().isDone()).isFalse();

    getErrorHandler().channelInactive(mock(ChannelHandlerContext.class));

    assertThat(session.getCloseFuture().isDone()).isTrue();
  }

  @Test
  public void itCompletesCloseFutureExceptionallyWhenTheConnectionIsClosed() throws Exception {
    ChannelInboundHandler errorHandler = getErrorHandler();

    Exception testException = new Exception();
    ChannelHandlerContext context = mock(ChannelHandlerContext.class);

    errorHandler.exceptionCaught(context, testException);

    verify(context).close();

    errorHandler.channelInactive(context);

    assertThat(session.getCloseFuture().isCompletedExceptionally()).isTrue();
    assertThatThrownBy(() -> session.getCloseFuture().get()).hasCause(testException);
  }

  @Test
  public void itDeterminesEncryptionStatusByCheckingPipeline() {
    assertThat(session.isEncrypted()).isFalse();

    when(pipeline.get(SslHandler.class)).thenReturn(new SslHandler(CONFIG.getSSLEngineSupplier().get()));

    assertThat(session.isEncrypted()).isTrue();
  }

  @Test
  public void itThrowsWhenStartTlsIsCalledIfEncryptionIsActive() {
    when(pipeline.get(SslHandler.class)).thenReturn(new SslHandler(CONFIG.getSSLEngineSupplier().get()));

    assertThatThrownBy(() -> session.startTls())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("This connection is already using TLS");
  }

  @Test
  public void itReturnsTheFailureResponseWhenStartTlsIsCalledIfTheServerReturnsAnError() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();

    responseFuture.complete(new SmtpResponse[] {FAIL_RESPONSE});

    assertThat(f.isDone());
    assertThat(f.get().code()).isEqualTo(FAIL_RESPONSE.code());
  }

  @Test
  public void itAddsAnSslHandlerToThePipelineIfTheStartTlsCommandSucceeds() throws Exception {
    session.startTls();

    responseFuture.complete(new SmtpResponse[] {OK_RESPONSE});

    verify(pipeline).addFirst(any(SslHandler.class));
  }

  @Test
  public void itFailsTheFutureIfTheTlsHandshakeFails() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();
    responseFuture.complete(new SmtpResponse[] {OK_RESPONSE});
    SslHandler sslHandler = getSslHandler();

    // fail the handshake
    Exception testException = new Exception();
    ((DefaultPromise<Channel>) sslHandler.handshakeFuture()).setFailure(testException);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get).hasCause(testException);

    verify(channel).close();
  }

  @Test
  public void itReturnsTheStartTlsResponseIfTheTlsHandshakeSucceeds() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();
    responseFuture.complete(new SmtpResponse[] {OK_RESPONSE});
    SslHandler sslHandler = getSslHandler();

    // the handshake succeeds
    ((DefaultPromise<Channel>) sslHandler.handshakeFuture()).setSuccess(channel);

    assertThat(f.isDone()).isTrue();
    assertThat(f.get().code()).isEqualTo(OK_RESPONSE.code());
  }

  private SslHandler getSslHandler() throws Exception {
    // get SslHandler if it was added to the pipeline
    ArgumentCaptor<ChannelHandler> captor = ArgumentCaptor.forClass(ChannelHandler.class);
    verify(pipeline).addFirst(captor.capture());
    SslHandler sslHandler = (SslHandler) captor.getValue();

    // mock and store the context so we can get the handshake future
    ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    when(context.executor()).thenReturn(ImmediateEventExecutor.INSTANCE);
    when(context.channel()).thenReturn(mock(Channel.class, Answers.RETURNS_MOCKS.get()));

    // add the handler but prevent the handshake from running automatically
    when(channel.isActive()).thenReturn(false);
    sslHandler.handlerAdded(context);

    return sslHandler;
  }

  private ChannelInboundHandler getErrorHandler() {
    ArgumentCaptor<ChannelHandler> captor = ArgumentCaptor.forClass(ChannelHandler.class);
    verify(pipeline).addLast(captor.capture());
    return (ChannelInboundHandler) captor.getValue();
  }
}
