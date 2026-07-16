import {Injectable, inject} from '@angular/core';
import {AiChatAttachmentService} from './ai-chat-attachment.service';
import {
  MAX_ATTACHMENT_BYTES,
  MAX_ATTACHMENTS,
  MAX_TOTAL_ATTACHMENT_BYTES
} from './ai-chat-panel.constants';
import {AiChatAttachment} from './ai-chat-panel.model';

export interface AiChatAttachmentQueueCallbacks {
  active(): boolean;
  added(): void;
  rejected(message: string): void;
}

@Injectable()
export class AiChatAttachmentQueueService {
  private attachmentService = inject(AiChatAttachmentService);
  private generation = 0;
  private pendingReads = 0;
  private pendingBytes = 0;

  get pending(): boolean {
    return this.pendingReads > 0;
  }

  addFiles(fileList: FileList | null | undefined, attachments: AiChatAttachment[],
           callbacks: AiChatAttachmentQueueCallbacks): void {
    if (!fileList || fileList.length === 0) return;
    Array.from(fileList).forEach(file => this.addFile(file, attachments, callbacks));
  }

  addFile(file: File, attachments: AiChatAttachment[],
          callbacks: AiChatAttachmentQueueCallbacks): void {
    const mediaType = this.attachmentService.attachmentMediaType(file);
    const rejection = this.rejectionMessage(file, mediaType, attachments);
    if (rejection) {
      callbacks.rejected(rejection);
      return;
    }

    const readGeneration = this.generation;
    this.pendingReads += 1;
    this.pendingBytes += file.size;
    this.attachmentService.readAttachment(file, mediaType).then(attachment => {
      if (callbacks.active() && readGeneration === this.generation) {
        attachments.push(attachment);
        callbacks.added();
      }
    }).catch(() => {
      if (callbacks.active() && readGeneration === this.generation) {
        callbacks.rejected('Could not read attachment: ' + file.name);
      }
    }).finally(() => {
      if (readGeneration === this.generation) {
        this.pendingReads = Math.max(0, this.pendingReads - 1);
        this.pendingBytes = Math.max(0, this.pendingBytes - file.size);
      }
    });
  }

  invalidate(): void {
    this.generation += 1;
    this.pendingReads = 0;
    this.pendingBytes = 0;
  }

  private rejectionMessage(file: File, mediaType: string,
                           attachments: AiChatAttachment[]): string | undefined {
    if (!this.attachmentService.isSupportedAttachment(file, mediaType)) {
      return 'Unsupported attachment type: ' + (mediaType || file.name);
    }
    if (file.size > MAX_ATTACHMENT_BYTES) {
      return 'Attachment is too large: ' + file.name;
    }
    if (attachments.length + this.pendingReads >= MAX_ATTACHMENTS) {
      return `A maximum of ${MAX_ATTACHMENTS} attachments is allowed.`;
    }
    const currentBytes = attachments.reduce((total, attachment) => total + attachment.size, 0);
    if (currentBytes + this.pendingBytes + file.size > MAX_TOTAL_ATTACHMENT_BYTES) {
      return 'Attachments exceed the 20 MB request limit.';
    }
    return undefined;
  }
}
