/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.server.state;

import io.atomix.catalyst.transport.Server;
import io.atomix.copycat.client.response.Response;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.request.AppendRequest;
import io.atomix.copycat.server.request.VoteRequest;
import io.atomix.copycat.server.response.AppendResponse;
import io.atomix.copycat.server.response.VoteResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;

import static org.testng.Assert.*;

/**
 * Candidate state tests.
 */
@Test
public class CandidateStateTest extends AbstractStateTest<AbstractCandidateState> {

  @BeforeMethod
  @Override
  void beforeMethod() throws Throwable {
    super.beforeMethod();
    state = new AbstractCandidateState(serverState.getController());
  }

  public void testCandidateAppendAndTransitionOnTerm() throws Throwable {
    runOnServer(() -> {
      int leader = serverState.getCluster().getVotingMemberStates().iterator().next().getMember().id();
      serverState.setTerm(1);
      AppendRequest request = AppendRequest.builder()
        .withTerm(2)
        .withLeader(leader)
        .withCommitIndex(0)
        .withGlobalIndex(0)
        .build();

      AppendResponse response = state.append(request).get();

      assertEquals(response.status(), Response.Status.OK);
      assertTrue(response.succeeded());
      assertEquals(serverState.getTerm(), 2L);
      assertEquals(serverState.getLeader().hashCode(), leader);
      assertEquals(response.term(), 2L);
      assertEquals(serverState.getState(), RaftStateType.FOLLOWER);
    });
  }

  public void testCandidateIncrementsTermVotesForSelfOnElection() throws Throwable {
    runOnServer(() -> {
      int self = serverState.getCluster().getMember().serverAddress().hashCode();
      serverState.setTerm(2);

      state.startElection();

      assertEquals(serverState.getTerm(), 3L);
      assertEquals(serverState.getLastVotedFor(), self);
    });
  }

  public void testCandidateVotesForSelfOnRequest() throws Throwable {
    runOnServer(() -> {
      int self = serverState.getCluster().getMember().serverAddress().hashCode();
      serverState.setTerm(2);

      state.startElection();

      assertEquals(serverState.getTerm(), 3L);

      VoteRequest request = VoteRequest.builder()
        .withTerm(3)
        .withCandidate(self)
        .withLogIndex(0)
        .withLogTerm(0)
        .build();

      VoteResponse response = state.vote(request).get();

      assertEquals(response.status(), Response.Status.OK);
      assertTrue(response.voted());
      assertEquals(serverState.getTerm(), 3L);
      assertEquals(serverState.getLastVotedFor(), self);
      assertEquals(response.term(), 3L);
    });
  }

  public void testCandidateVotesAndTransitionsOnTerm() throws Throwable {
    runOnServer(() -> {
      int candidate = serverState.getCluster().getVotingMembers().iterator().next().id();
      serverState.setTerm(1);

      state.startElection();

      assertEquals(serverState.getTerm(), 2L);

      VoteRequest request = VoteRequest.builder()
        .withTerm(3)
        .withCandidate(candidate)
        .withLogTerm(0)
        .withLogIndex(0)
        .build();

      VoteResponse response = state.vote(request).get();

      assertEquals(response.status(), Response.Status.OK);
      assertTrue(response.voted());
      assertEquals(serverState.getTerm(), 3L);
      assertEquals(serverState.getLastVotedFor(), candidate);
      assertEquals(response.term(), 3L);
      assertEquals(serverState.getState(), RaftStateType.FOLLOWER);
    });
  }

  public void testCandidateRejectsVoteAndTransitionsOnTerm() throws Throwable {
    runOnServer(() -> {
      int candidate = serverState.getCluster().getVotingMembers().iterator().next().id();
      serverState.setTerm(1);

      append(2, 1);

      state.startElection();

      assertEquals(serverState.getTerm(), 2L);

      VoteRequest request = VoteRequest.builder()
        .withTerm(3)
        .withCandidate(candidate)
        .withLogTerm(0)
        .withLogIndex(0)
        .build();

      VoteResponse response = state.vote(request).get();

      assertEquals(response.status(), Response.Status.OK);
      assertFalse(response.voted());
      assertEquals(serverState.getTerm(), 3L);
      assertEquals(serverState.getLastVotedFor(), 0);
      assertEquals(response.term(), 3L);
      assertEquals(serverState.getState(), RaftStateType.FOLLOWER);
    });
  }

  public void testCandidateTransitionsToLeaderOnElection() throws Throwable {
    serverState.onStateChange(state -> {
      if (state == RaftStateType.LEADER)
        resume();
    });

    runOnServer(() -> {
      for (Member member : serverState.getCluster().getMembers()) {
        Server server = transport.server();
        server.listen(member.serverAddress(), c -> {
          c.handler(VoteRequest.class, request -> CompletableFuture.completedFuture(VoteResponse.builder()
            .withTerm(2)
            .withVoted(true)
            .build()));
        }).thenRunAsync(this::resume);
      }
    });

    await(1000, serverState.getCluster().getMembers().size());

    runOnServer(() -> {
      int self = serverState.getCluster().getMember().serverAddress().hashCode();
      serverState.setTerm(1);

      state.startElection();

      assertEquals(serverState.getTerm(), 2L);
      assertEquals(serverState.getLastVotedFor(), self);
    });
    await(1000);
  }

  public void testCandidateTransitionsToFollowerOnRejection() throws Throwable {
    serverState.onStateChange(state -> {
      if (state == RaftStateType.FOLLOWER)
        resume();
    });

    runOnServer(() -> {
      for (Member member : serverState.getCluster().getMembers()) {
        Server server = transport.server();
        server.listen(member.serverAddress(), c -> {
          c.handler(VoteRequest.class, request -> CompletableFuture.completedFuture(VoteResponse.builder()
            .withTerm(2)
            .withVoted(false)
            .build()));
        }).thenRunAsync(this::resume);
      }
    });

    await(1000, serverState.getCluster().getMembers().size());

    runOnServer(() -> {
      int self = serverState.getCluster().getMember().serverAddress().hashCode();
      serverState.setTerm(1);

      state.startElection();

      assertEquals(serverState.getTerm(), 2L);
      assertEquals(serverState.getLastVotedFor(), self);
    });
    await(1000);
  }

}
