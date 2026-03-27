import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';

@Component({
  standalone: false,
  selector: 'score-release-import-dialog',
  templateUrl: './release-import-dialog.component.html',
  styleUrls: ['./release-import-dialog.component.css']
})
export class ReleaseImportDialogComponent {
  dialogRef = inject<MatDialogRef<ReleaseImportDialogComponent>>(MatDialogRef);

  @ViewChild('fileInput', { static: false }) fileInput: ElementRef<HTMLInputElement>;

  dragging = false;
  selectedFile: File | null = null;

  onDragOver(event: DragEvent) {
    event.preventDefault();
    this.dragging = true;
  }

  onDragLeave(event: DragEvent) {
    event.preventDefault();
    this.dragging = false;
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.dragging = false;
    this.setFile(event.dataTransfer?.files?.item(0) ?? null);
  }

  browse() {
    this.fileInput?.nativeElement.click();
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    this.setFile(input.files?.item(0) ?? null);
  }

  clearFile() {
    this.selectedFile = null;
    if (this.fileInput) {
      this.fileInput.nativeElement.value = '';
    }
  }

  submit() {
    if (this.selectedFile) {
      this.dialogRef.close(this.selectedFile);
    }
  }

  private setFile(file: File | null) {
    if (!file) {
      return;
    }
    if (!file.name.toLowerCase().endsWith('.zip') && file.type !== 'application/zip') {
      return;
    }
    this.selectedFile = file;
  }
}
