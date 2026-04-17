package com.niren.drama.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {
    /** user | assistant | system */
    private String role;
    private String content;
}
