/**
 * MIT No Attribution
 *
 *Copyright 2024 Giuseppe Valente <valentepeppe@gmail.com>
 *
 *Permission is hereby granted, free of charge, to any person obtaining a copy of this
 *software and associated documentation files (the "Software"), to deal in the Software
 *without restriction, including without limitation the rights to use, copy, modify,
 *merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 *permit persons to whom the Software is furnished to do so.
 *
 *THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 *INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 *PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 *HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 *OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 *SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package it.uniroma1.databaseservice.messaging;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.uniroma1.databaseservice.entities.Authority;
import it.uniroma1.databaseservice.entities.Member;
import it.uniroma1.databaseservice.repositories.AuthorityRepository;
import it.uniroma1.databaseservice.repositories.MemberRepository;
import jakarta.transaction.Transactional;

@Service
public class MessageListener {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @RabbitListener(queues = { "${queue.rabbitmq.listener.name}" })
    @SendTo("user_exchange/${binding.rabbitmq.key}")
    public String receiveMessage(String message) throws JsonProcessingException {

        ACK<Object> replyMessage = new ACK<Object>();
        String response = "";
        try {
            if (message != null) {
                ObjectMapper om = new ObjectMapper();
                MessagePayload mp = (MessagePayload) om.readValue(message, MessagePayload.class);
                Member m = null;
                if (mp != null) {
                    switch (mp.getOperationType()) {
                        case INSERT:
                            m = mp.getUser();
                            replyMessage = insertMember(m);
                            break;
                        case DELETE:
                            m = mp.getUser();
                            replyMessage = deleteMember(m.getId());
                            break;
                        default:
                            replyMessage.setMessage("Operation not supported");
                            replyMessage.setPayload(0L);
                            replyMessage.setSuccess(false);
                            break;
                    }
                } else {
                    replyMessage.setMessage("Unparsable message");
                    replyMessage.setPayload(0L);
                    replyMessage.setSuccess(false);
                }
            } else {
                replyMessage.setMessage("Unparsable message");
                replyMessage.setPayload(0L);
                replyMessage.setSuccess(false);
            }

        } catch (Exception e) {
            replyMessage.setMessage(e.getMessage());
            replyMessage.setPayload(0L);
            replyMessage.setSuccess(false);
        } finally {
            ObjectMapper om = new ObjectMapper();
            response = om.writeValueAsString(replyMessage);
        }

        // Send back the ACK
        return response;
    }

    /**
     * Private method to insert the user in the database, then build the ACK message
     * 
     * @param m the member to insert
     * @return the ACK related to the insert operation
     * @throws Exception 
     */
    @Transactional
    private ACK<Object> insertMember(Member m) throws Exception {

        ACK<Object> replyMessage = new ACK<Object>();
        try {
            // Insert the member if it doesn't exist
            if (memberRepository.findByUsername(m.getUsername()) == null) {
                BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();
                m.setPassword(bCryptPasswordEncoder.encode(m.getPassword()));
                Set<Authority> roles = Collections.synchronizedSet(new HashSet<Authority>());
                if (m.getAuthorities() != null && !m.getAuthorities().isEmpty()) {
                    for (Authority a : m.getAuthorities()) {
                        if (a != null && a.getAuthorityName() != null) {
                            a = authorityRepository
                                    .findByAuthorityName(a.getAuthorityName().toUpperCase());
                        }
                        if (a != null) {
                            roles.add(a);
                        }
                    }
                }
                m.setAuthorities(roles);
                m = memberRepository.save(m);
                replyMessage.setMessage("Ok");
                replyMessage.setPayload(m.getId());
                replyMessage.setSuccess(true);
            } else {
                replyMessage.setMessage("User exist");
                replyMessage.setPayload(0L);
                replyMessage.setSuccess(false);
            }
            return replyMessage;

        } catch(Exception e) {
            //Raise the exception to the caller to manage it
            throw new Exception(e);
        } 
    }

    @Transactional
    private ACK<Object> deleteMember(long id) throws Exception {

        try {
            ACK<Object> replyMessage = new ACK<Object>();
            Member m = memberRepository.findById(id);
            if(m != null) {

                String username = m.getUsername();
                memberRepository.delete(m);
                replyMessage.setMessage(String.format("User %s deleted", username));
                replyMessage.setSuccess(true);
                replyMessage.setPayload(null);

            } else {
                replyMessage.setMessage("User does not exist");
                replyMessage.setSuccess(false);
                replyMessage.setPayload(null);
            }
            return replyMessage;


        } catch(Exception e) {
            //Raise the exception to the caller to manage it
            throw new Exception(e);
        }
    }
}